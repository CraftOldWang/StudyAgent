package com.studyagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.AgentInvocationScopeFactory;
import com.studyagent.agent.integration.LearningContextCompactor;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.ReviewCardMapper;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import com.studyagent.model.Quiz;
import com.studyagent.model.ReviewCard;
import com.studyagent.review.ReviewCardService;
import io.agentscope.core.agent.RuntimeContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LearningFlowService {

    private final LearningPlanService learningPlanService;
    private final LearningPersistenceService persistence;
    private final LearningModelGateway modelGateway;
    private final LearningTraceService traceService;
    private final LearningContextCompactor contextCompactor;
    private final AgentInvocationScopeFactory scopeFactory;
    private final ReviewCardService reviewCardService;
    private final ReviewCardMapper reviewCardMapper;
    private final ObjectMapper objectMapper;

    public CreatedSession createSession(Long userId, Long knowledgeBaseId, String learningGoal) {
        requireId(knowledgeBaseId, "knowledgeBaseId 不能为空");
        String traceId = traceService.start();
        traceService.record(userId, traceId, null, "PLAN", "MODEL_CALL", "请求 DeepSeek 生成学习计划", "STARTED");
        try {
            List<LearningPlanItem> plan = learningPlanService.generatePlan(learningGoal);
            traceService.record(userId, traceId, null, "PLAN", "MODEL_CALL", "DeepSeek 已返回学习计划", "SUCCEEDED");
            LearningSession session = persistence.create(
                    userId,
                    knowledgeBaseId,
                    learningGoal.trim(),
                    UUID.randomUUID().toString(),
                    plan);
            traceService.record(userId, traceId, session.getId(), "PLAN", "STATE_TRANSITION", "学习会话已创建，首个知识点为 NEW", "SUCCEEDED");
            return new CreatedSession(traceId, session);
        } catch (RuntimeException ex) {
            traceService.record(userId, traceId, null, "PLAN", "MODEL_CALL", failureMessage(ex), "FAILED");
            throw ex;
        }
    }

    public TracedAnswer explain(Long userId, Long sessionId) {
        LearningSession session = persistence.requireSession(userId, sessionId);
        KnowledgePoint point = persistence.requireActivePoint(session);
        requireStatus(point, KnowledgePointStatus.NEW);
        String traceId = traceService.start();
        try {
            traceService.record(userId, traceId, sessionId, "EXPLAIN", "MODEL_CALL", "主 Agent 开始讲解", "STARTED");
            String answer = modelGateway.explain(session, point);
            traceService.record(userId, traceId, sessionId, "EXPLAIN", "MODEL_CALL", "主 Agent 完成讲解", "SUCCEEDED");
            persistence.saveExplanationAndAdvance(session, point, answer);
            traceService.record(userId, traceId, sessionId, "EXPLAIN", "TOOL_CALL", "知识点 NEW → EXPLAINING", "SUCCEEDED");
            return new TracedAnswer(traceId, answer);
        } catch (RuntimeException ex) {
            fail(session, point, traceId, "EXPLAIN", ex);
            throw ex;
        }
    }

    public TracedAnswer answerQuestion(Long userId, Long sessionId, String question) {
        LearningSession session = persistence.requireSession(userId, sessionId);
        KnowledgePoint point = persistence.requireActivePoint(session);
        KnowledgePointStatus status = parseStatus(point);
        if (status != KnowledgePointStatus.EXPLAINING && status != KnowledgePointStatus.QUIZZING) {
            throw new BusinessException("仅 EXPLAINING 或 QUIZZING 状态允许答疑");
        }
        String traceId = traceService.start();
        try {
            traceService.record(userId, traceId, sessionId, "QUESTION", "MODEL_CALL", "主 Agent 开始答疑", "STARTED");
            String answer = modelGateway.answerQuestion(session, point, question);
            persistence.clearFailure(session, point);
            traceService.record(userId, traceId, sessionId, "QUESTION", "MODEL_CALL", "主 Agent 完成答疑，知识点状态保持 " + status, "SUCCEEDED");
            return new TracedAnswer(traceId, answer);
        } catch (RuntimeException ex) {
            fail(session, point, traceId, "QUESTION", ex);
            throw ex;
        }
    }

    public GeneratedQuiz generateQuiz(Long userId, Long sessionId) {
        LearningSession session = persistence.requireSession(userId, sessionId);
        KnowledgePoint point = persistence.requireActivePoint(session);
        requireStatus(point, KnowledgePointStatus.EXPLAINING);
        String traceId = traceService.start();
        try {
            traceService.record(userId, traceId, sessionId, "QUIZ", "MODEL_CALL", "主 Agent 开始生成五题测验", "STARTED");
            List<QuizQuestionDraft> questions = modelGateway.generateQuiz(session, point);
            Quiz quiz = persistence.saveQuizAndAdvance(session, point, toJson(questions));
            traceService.record(userId, traceId, sessionId, "QUIZ", "TOOL_CALL", "五题测验已持久化，知识点 EXPLAINING → QUIZZING", "SUCCEEDED");
            return new GeneratedQuiz(traceId, quiz, questions);
        } catch (RuntimeException ex) {
            fail(session, point, traceId, "QUIZ", ex);
            throw ex;
        }
    }

    public QuizScore submitQuiz(Long userId, Long sessionId, List<String> answers) {
        LearningSession session = persistence.requireSession(userId, sessionId);
        KnowledgePoint point = persistence.requireActivePoint(session);
        requireStatus(point, KnowledgePointStatus.QUIZZING);
        if (answers == null || answers.size() != 5 || answers.stream().anyMatch(answer -> answer == null || answer.isBlank())) {
            throw new BusinessException("必须一次提交 5 个非空答案");
        }
        String traceId = traceService.start();
        try {
            Quiz quiz = persistence.requireQuiz(point);
            List<QuizQuestionDraft> questions = readQuestions(quiz.getQuestionsJson());
            List<QuizFeedback> feedback = new ArrayList<>(5);
            int correctCount = 0;
            for (int index = 0; index < questions.size(); index++) {
                QuizQuestionDraft question = questions.get(index);
                boolean correct = question.correctAnswer().equals(answers.get(index));
                if (correct) {
                    correctCount++;
                }
                feedback.add(new QuizFeedback(index, correct, question.correctAnswer(), question.explanation()));
            }
            int score = correctCount * 20;
            persistence.saveQuizResultAndAdvance(session, quiz, point, toJson(answers), score, toJson(feedback));
            traceService.record(userId, traceId, sessionId, "QUIZ", "TOOL_CALL", "测验已评分且无及格门槛，知识点 QUIZZING → CARD_GENERATING", "SUCCEEDED");
            return new QuizScore(traceId, quiz.getId(), score, List.copyOf(feedback));
        } catch (RuntimeException ex) {
            fail(session, point, traceId, "QUIZ", ex);
            throw ex;
        }
    }

    public GeneratedCards generateCardsAndComplete(Long userId, Long sessionId) {
        LearningSession session = persistence.requireSession(userId, sessionId);
        KnowledgePoint point = persistence.requireActivePoint(session);
        requireStatus(point, KnowledgePointStatus.CARD_GENERATING);
        String traceId = traceService.start();
        try {
            List<ReviewCard> cards = existingCards(point.getId());
            if (!cards.isEmpty() && cards.size() != 3) {
                throw new BusinessException("当前知识点已保存的复习卡数量不是 3，不能完成");
            }
            if (cards.isEmpty()) {
                traceService.record(userId, traceId, sessionId, "CARD", "MODEL_CALL", "主 Agent 开始生成三张复习卡", "STARTED");
                List<GeneratedCard> drafts = modelGateway.generateCards(session, point);
                cards = reviewCardService.writeBatch(
                        userId,
                        point.getId(),
                        session.getKnowledgeBaseId(),
                        drafts.stream().map(draft -> new ReviewCardService.Draft(
                                draft.front(), draft.back(), draft.sourceChunkId())).toList());
                traceService.record(userId, traceId, sessionId, "CARD", "TOOL_CALL", "三张复习卡已持久化", "SUCCEEDED");
            }
            RuntimeContext runtimeContext = scopeFactory.createRuntimeContext(
                    session.getAgentscopeSessionId(), userId, session.getKnowledgeBaseId(), point.getId());
            contextCompactor.compact(runtimeContext);
            traceService.record(userId, traceId, sessionId, "COMPACTION", "MODEL_CALL", "完成 turn 已 one-off 压缩并保存 AgentState", "SUCCEEDED");
            persistence.completePoint(session, point);
            traceService.record(userId, traceId, sessionId, "COMPLETE", "STATE_TRANSITION", "知识点 CARD_GENERATING → COMPLETED", "SUCCEEDED");
            return new GeneratedCards(traceId, point.getId(), cards);
        } catch (RuntimeException ex) {
            fail(session, point, traceId, "CARD", ex);
            throw ex;
        }
    }

    public LearningSession loadSession(Long userId, Long sessionId) {
        return persistence.requireSession(userId, sessionId);
    }

    public List<KnowledgePoint> listPoints(Long sessionId) {
        return persistence.listPoints(sessionId);
    }

    public Quiz findQuiz(KnowledgePoint point) {
        try {
            return persistence.requireQuiz(point);
        } catch (BusinessException ex) {
            return null;
        }
    }

    public List<ReviewCard> existingCards(Long knowledgePointId) {
        return reviewCardMapper.selectList(new LambdaQueryWrapper<ReviewCard>()
                .eq(ReviewCard::getKnowledgePointId, knowledgePointId)
                .orderByAsc(ReviewCard::getCreatedAt));
    }

    public List<QuizQuestionDraft> readQuestions(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ex) {
            throw new BusinessException("读取测验题失败: " + ex.getMessage());
        }
    }

    public List<QuizFeedback> readFeedback(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ex) {
            throw new BusinessException("读取测验反馈失败: " + ex.getMessage());
        }
    }

    private void fail(LearningSession session, KnowledgePoint point, String traceId, String stage, RuntimeException ex) {
        persistence.recordFailure(session, point, failureMessage(ex));
        traceService.record(session.getUserId(), traceId, session.getId(), stage, "FAILURE", failureMessage(ex), "FAILED");
    }

    private void requireStatus(KnowledgePoint point, KnowledgePointStatus expected) {
        if (parseStatus(point) != expected) {
            throw new BusinessException("当前知识点必须处于 " + expected + " 状态");
        }
    }

    private KnowledgePointStatus parseStatus(KnowledgePoint point) {
        try {
            return KnowledgePointStatus.valueOf(point.getStatus());
        } catch (RuntimeException ex) {
            throw new BusinessException("未知知识点状态: " + point.getStatus());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException("学习流程 JSON 序列化失败: " + ex.getMessage());
        }
    }

    private void requireId(Long value, String message) {
        if (value == null) {
            throw new BusinessException(message);
        }
    }

    private String failureMessage(RuntimeException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    public record CreatedSession(String traceId, LearningSession session) { }
    public record TracedAnswer(String traceId, String answer) { }
    public record GeneratedQuiz(String traceId, Quiz quiz, List<QuizQuestionDraft> questions) { }
    public record QuizScore(String traceId, Long quizId, int score, List<QuizFeedback> feedback) { }
    public record GeneratedCards(String traceId, Long knowledgePointId, List<ReviewCard> cards) { }
}
