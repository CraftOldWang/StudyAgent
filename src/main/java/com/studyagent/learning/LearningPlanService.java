package com.studyagent.learning;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AgentScopeModelConfiguration;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 通过当前 AgentScope DeepSeek 模型生成并校验学习计划。
 *
 * <p>计划生成是一次直接的模型调用，故意不经过 HarnessAgent；本任务只需要 ChatModel 的
 * formatter 适配和严格 JSON 解析，不引入 Agent loop、持久化或重试策略。</p>
 */
@Service
public class LearningPlanService {

    private static final Set<String> PLAN_FIELDS = Set.of("topic", "subtopics", "estimatedMinutes");
    private static final String SYSTEM_PROMPT = """
            你是学习计划生成器。根据用户的学习目标拆解出循序渐进的知识点。
            只能输出严格 JSON 数组，禁止 Markdown 代码围栏、解释文字或其它字段。
            每个数组元素必须恰好包含 topic、subtopics、estimatedMinutes 三个字段：
            topic 是非空字符串，subtopics 是字符串数组，estimatedMinutes 是大于 0 的整数。
            计划应覆盖用户目标，并按学习顺序排列；通常生成 3 到 5 个知识点。
            输出格式示例：
            [{"topic":"示例主题","subtopics":["示例子主题"],"estimatedMinutes":30}]
            """;

    private final Model model;
    private final ObjectMapper objectMapper;

    public LearningPlanService(
            @Qualifier(AgentScopeModelConfiguration.PRIMARY_MODEL_BEAN_NAME) Model model,
            ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据非空学习目标生成计划。
     */
    public List<LearningPlanItem> generatePlan(String learningGoal) {
        String goal = requireLearningGoal(learningGoal);
        String rawOutput = callModel(goal);
        return parsePlan(rawOutput);
    }

    private String requireLearningGoal(String learningGoal) {
        if (learningGoal == null || learningGoal.isBlank()) {
            throw new BusinessException("学习目标不能为空");
        }
        return learningGoal.trim();
    }

    private String callModel(String learningGoal) {
        List<Msg> messages = List.of(
                Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent(SYSTEM_PROMPT)
                        .build(),
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent("学习目标：\n" + learningGoal)
                        .build());

        List<ChatResponse> responses;
        try {
            responses = model.stream(
                            messages,
                            List.of(),
                            GenerateOptions.builder().stream(false).build())
                    .collectList()
                    .block();
        } catch (RuntimeException ex) {
            throw new BusinessException("调用 DeepSeek 生成学习计划失败: " + failureMessage(ex));
        }

        if (responses == null || responses.isEmpty()) {
            throw new BusinessException("DeepSeek 未返回学习计划内容");
        }

        String text = responses.stream()
                .filter(response -> response != null && response.getContent() != null)
                .flatMap(response -> response.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .filter(value -> value != null && !value.isEmpty())
                .collect(Collectors.joining())
                .trim();
        if (text.isEmpty()) {
            throw new BusinessException("DeepSeek 未返回有效的学习计划内容");
        }
        return text;
    }

    private List<LearningPlanItem> parsePlan(String rawOutput) {
        JsonNode root = readJsonArray(rawOutput);
        if (root.isEmpty()) {
            throw new BusinessException("学习计划 JSON 数组不能为空");
        }

        List<LearningPlanItem> plan = new ArrayList<>();
        for (int index = 0; index < root.size(); index++) {
            plan.add(parseItem(root.get(index), index));
        }
        return List.copyOf(plan);
    }

    private JsonNode readJsonArray(String rawOutput) {
        try (JsonParser parser = objectMapper.getFactory().createParser(rawOutput)) {
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isArray()) {
                throw new BusinessException("学习计划输出必须是 JSON 数组");
            }
            if (parser.nextToken() != null) {
                throw new BusinessException("学习计划输出包含 JSON 数组之外的内容");
            }
            return root;
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException("解析 DeepSeek 学习计划 JSON 失败: " + ex.getMessage());
        }
    }

    private LearningPlanItem parseItem(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            throw new BusinessException("学习计划第 " + (index + 1) + " 项必须是 JSON 对象");
        }

        Set<String> fields = new LinkedHashSet<>();
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            fields.add(fieldNames.next());
        }
        if (!fields.equals(PLAN_FIELDS)) {
            throw new BusinessException(
                    "学习计划第 " + (index + 1) + " 项必须恰好包含 topic、subtopics、estimatedMinutes");
        }

        JsonNode topicNode = node.get("topic");
        if (topicNode == null || !topicNode.isTextual() || topicNode.asText().isBlank()) {
            throw new BusinessException("学习计划第 " + (index + 1) + " 项的 topic 必须是非空字符串");
        }

        JsonNode subtopicsNode = node.get("subtopics");
        if (subtopicsNode == null || !subtopicsNode.isArray()) {
            throw new BusinessException("学习计划第 " + (index + 1) + " 项的 subtopics 必须是字符串数组");
        }
        List<String> subtopics = new ArrayList<>();
        for (JsonNode subtopicNode : subtopicsNode) {
            if (!subtopicNode.isTextual() || subtopicNode.asText().isBlank()) {
                throw new BusinessException("学习计划第 " + (index + 1) + " 项的 subtopics 必须只包含非空字符串");
            }
            subtopics.add(subtopicNode.asText().trim());
        }

        JsonNode minutesNode = node.get("estimatedMinutes");
        if (minutesNode == null || !minutesNode.isIntegralNumber() || !minutesNode.canConvertToInt()
                || minutesNode.asInt() <= 0) {
            throw new BusinessException("学习计划第 " + (index + 1) + " 项的 estimatedMinutes 必须是正整数");
        }
        return new LearningPlanItem(topicNode.asText().trim(), List.copyOf(subtopics), minutesNode.asInt());
    }

    private String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
