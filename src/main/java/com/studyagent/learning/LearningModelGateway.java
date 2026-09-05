package com.studyagent.learning;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.AgentInvocationScopeFactory;
import com.studyagent.agent.integration.KnowledgeSearchExecution;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LearningModelGateway {

    private final HarnessAgent harnessAgent;
    private final AgentInvocationScopeFactory scopeFactory;
    private final ObjectMapper objectMapper;

    public String explain(LearningSession session, KnowledgePoint point) {
        return call(session, point, KnowledgePointStatus.EXPLAINING, true, """
                加载 explain skill，围绕当前知识点讲解。必须先用 knowledge_search 检索当前知识库，
                只引用工具实际返回的 chunkId；无资料时明确说明，不得编造来源。
                完成讲解后调用 learning_state_transition，target=EXPLAINING；服务端只在整个 turn 成功后提交状态。
                学习目标：%s
                当前知识点：%s
                子主题 JSON：%s
                """.formatted(session.getLearningGoal(), point.getTopic(), point.getSubtopicsJson())).text();
    }

    public String answerQuestion(LearningSession session, KnowledgePoint point, String question) {
        if (question == null || question.isBlank()) {
            throw new BusinessException("问题不能为空");
        }
        return call(session, point, null, false, """
                回答用户对当前知识点的疑问。需要资料依据时调用 knowledge_search，禁止编造来源。
                当前状态是 %s，回答疑问不得改变知识点状态。
                当前知识点：%s
                用户问题：%s
                """.formatted(point.getStatus(), point.getTopic(), question.trim())).text();
    }

    public List<QuizQuestionDraft> generateQuiz(LearningSession session, KnowledgePoint point) {
        AgentCall result = call(session, point, KnowledgePointStatus.QUIZZING, true, """
                加载 quiz skill，为当前知识点生成恰好 5 道四选一题。必须先调用 knowledge_search。
                最终只能输出 JSON 数组，不要 Markdown。每项字段必须为 question、options、correctAnswer、
                explanation、sourceChunkId；options 恰好 4 个非空字符串，correctAnswer 必须等于其中一个选项，
                sourceChunkId 必须来自实际检索结果。输出前调用 learning_state_transition，target=QUIZZING；
                服务端只在整个 turn 成功后提交状态。当前知识点：%s
                """.formatted(point.getTopic()));
        return parseQuiz(result.text(), result.retrievedChunkIds());
    }

    public List<GeneratedCard> generateCards(LearningSession session, KnowledgePoint point) {
        AgentCall result = call(session, point, KnowledgePointStatus.COMPLETED, true, """
                加载 card skill，为当前知识点生成恰好 3 张 Anki 风格复习卡。必须先调用 knowledge_search。
                本次只生成 JSON 草稿，不要调用 review_card_write；服务端会在校验后一次性持久化。
                最终只能输出 JSON 数组，不要 Markdown。每项字段必须为 front、back、sourceChunkId，
                有实际检索来源时 sourceChunkId 必须来自工具结果；没有来源时可为 null，禁止伪造。
                输出前调用 learning_state_transition，target=COMPLETED；服务端会先保存卡片并压缩上下文，再提交完成状态。
                当前知识点：%s
                """.formatted(point.getTopic()));
        try {
            JsonNode root = readStrictJson(result.text());
            if (!root.isArray() || root.size() != 3) {
                throw new BusinessException("复习卡输出必须是恰好 3 项的 JSON 数组");
            }
            List<GeneratedCard> cards = new ArrayList<>(3);
            for (JsonNode node : root) {
                String sourceChunkId = optionalText(node, "sourceChunkId");
                requireRetrievedSource(sourceChunkId, result.retrievedChunkIds());
                cards.add(new GeneratedCard(
                        requiredText(node, "front"),
                        requiredText(node, "back"),
                        sourceChunkId));
            }
            return List.copyOf(cards);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("解析复习卡 JSON 失败: " + ex.getMessage());
        }
    }

    private AgentCall call(
            LearningSession session,
            KnowledgePoint point,
            KnowledgePointStatus expectedTarget,
            boolean requireKnowledgeSearch,
            String prompt) {
        RuntimeContext context = scopeFactory.createRuntimeContext(
                session.getAgentscopeSessionId(),
                session.getUserId(),
                session.getKnowledgeBaseId(),
                point.getId());
        LearningTransitionIntent intent = new LearningTransitionIntent(
                parseStatus(point.getStatus()), expectedTarget);
        context.put(LearningTransitionIntent.class, intent);
        try {
            Msg response = harnessAgent.call(prompt, context).block();
            if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
                throw new BusinessException("DeepSeek 未返回有效内容");
            }
            intent.requireRequested();
            KnowledgeSearchExecution execution = context.get(KnowledgeSearchExecution.class);
            if (requireKnowledgeSearch && execution == null) {
                throw new BusinessException("Agent 未调用 knowledge_search 检索当前知识库");
            }
            Set<String> retrievedChunkIds = execution == null
                    ? Set.of()
                    : execution.retrievedChunkIds();
            return new AgentCall(response.getTextContent().trim(), retrievedChunkIds);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException("AgentScope 调用 DeepSeek 失败: " + failureMessage(ex));
        }
    }

    private List<QuizQuestionDraft> parseQuiz(String raw, Set<String> retrievedChunkIds) {
        try {
            JsonNode root = readStrictJson(raw);
            if (!root.isArray() || root.size() != 5) {
                throw new BusinessException("测验输出必须是恰好 5 项的 JSON 数组");
            }
            List<QuizQuestionDraft> questions = new ArrayList<>(5);
            for (JsonNode node : root) {
                JsonNode optionsNode = node.get("options");
                if (optionsNode == null || !optionsNode.isArray() || optionsNode.size() != 4) {
                    throw new BusinessException("每道测验题必须包含恰好 4 个选项");
                }
                List<String> options = new ArrayList<>(4);
                optionsNode.forEach(option -> {
                    if (!option.isTextual() || option.asText().isBlank()) {
                        throw new BusinessException("测验选项必须是非空字符串");
                    }
                    options.add(option.asText().trim());
                });
                String correctAnswer = requiredText(node, "correctAnswer");
                if (!options.contains(correctAnswer)) {
                    throw new BusinessException("测验正确答案必须等于一个选项");
                }
                String sourceChunkId = requiredText(node, "sourceChunkId");
                requireRetrievedSource(sourceChunkId, retrievedChunkIds);
                questions.add(new QuizQuestionDraft(
                        requiredText(node, "question"),
                        List.copyOf(options),
                        correctAnswer,
                        requiredText(node, "explanation"),
                        sourceChunkId));
            }
            return List.copyOf(questions);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("解析测验 JSON 失败: " + ex.getMessage());
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new BusinessException(field + " 必须是非空字符串");
        }
        return value.asText().trim();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new BusinessException(field + " 必须是字符串或 null");
        }
        return value.asText().isBlank() ? null : value.asText().trim();
    }

    private void requireRetrievedSource(String sourceChunkId, Set<String> retrievedChunkIds) {
        if (sourceChunkId != null && !retrievedChunkIds.contains(sourceChunkId)) {
            throw new BusinessException("sourceChunkId 不在本次 knowledge_search 结果中: " + sourceChunkId);
        }
    }

    private JsonNode readStrictJson(String raw) throws java.io.IOException {
        return objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(raw);
    }

    private KnowledgePointStatus parseStatus(String raw) {
        try {
            return KnowledgePointStatus.valueOf(raw);
        } catch (RuntimeException ex) {
            throw new BusinessException("未知知识点状态: " + raw);
        }
    }

    private String failureMessage(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private record AgentCall(String text, Set<String> retrievedChunkIds) {
    }
}
