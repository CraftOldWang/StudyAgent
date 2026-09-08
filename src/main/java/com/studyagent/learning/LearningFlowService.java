package com.studyagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.AgentInvocationScopeFactory;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.ReviewCardMapper;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import com.studyagent.model.Quiz;
import com.studyagent.model.ReviewCard;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LearningFlowService {

    private final LearningPlanService learningPlanService;
    private final LearningPersistenceService persistence;
    private final LearningConversationService conversation;
    private final LearningTraceService traceService;
    private final AgentInvocationScopeFactory scopeFactory;
    private final ReviewCardMapper reviewCardMapper;
    private final ObjectMapper objectMapper;

    public CreatedSession createSession(Long userId, Long knowledgeBaseId, String learningGoal) {
        requireId(knowledgeBaseId, "knowledgeBaseId 不能为空");
        scopeFactory.validateKnowledgeBaseScope(userId, knowledgeBaseId);
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
        var turn = shortcut(userId, sessionId, "请开始讲解当前知识点。", "EXPLANATION");
        return new TracedAnswer(turn.getTraceId(), turn.getAssistantMessage());
    }

    public TracedAnswer answerQuestion(Long userId, Long sessionId, String question) {
        var turn = shortcut(userId, sessionId, question, null);
        return new TracedAnswer(turn.getTraceId(), turn.getAssistantMessage());
    }

    public GeneratedQuiz generateQuiz(Long userId, Long sessionId) {
        var turn = shortcut(userId, sessionId, "我准备好了，请生成当前知识点的五题测验。", "QUIZ");
        var point = persistence.requireActivePoint(persistence.requireSession(userId, sessionId));
        var quiz = persistence.requireQuiz(point);
        return new GeneratedQuiz(turn.getTraceId(), quiz, readQuestions(quiz.getQuestionsJson()));
    }

    public QuizScore submitQuiz(Long userId, Long sessionId, List<String> answers) {
        var point = persistence.requireActivePoint(persistence.requireSession(userId, sessionId));
        var quiz = persistence.requireQuiz(point);
        var questions = readQuestions(quiz.getQuestionsJson());
        if (answers == null || answers.size() != 5) { throw new BusinessException("必须一次提交五个答案"); }
        StringBuilder message = new StringBuilder("提交答案：");
        for (int i = 0; i < 5; i++) {
            int index = questions.get(i).options().indexOf(answers.get(i));
            if (index < 0) { throw new BusinessException("答案必须是对应题目的选项"); }
            message.append(i + 1).append('.').append((char) ('A' + index)).append(' ');
        }
        var turn = shortcut(userId, sessionId, message.toString(), "GRADE");
        quiz = persistence.requireQuiz(point);
        return new QuizScore(turn.getTraceId(), quiz.getId(), quiz.getScore(), readFeedback(quiz.getFeedbackJson()));
    }

    public GeneratedCards generateCardsAndComplete(Long userId, Long sessionId) {
        var turn = shortcut(userId, sessionId, "请为当前知识点生成三张复习卡并完成本知识点。", "CARDS");
        return new GeneratedCards(turn.getTraceId(), turn.getKnowledgePointId(), existingCards(turn.getKnowledgePointId()));
    }

    private com.studyagent.model.LearningTurn shortcut(Long userId, Long sessionId, String message, String expectedArtifact) {
        var turn = conversation.message(userId, sessionId, UUID.randomUUID().toString(), message, event -> { });
        if (!"SUCCEEDED".equals(turn.getStatus())) {
            throw new BusinessException("回合 " + turn.getId() + " 未完成：" + turn.getErrorMessage());
        }
        if (expectedArtifact != null) {
            try {
                if (!expectedArtifact.equals(objectMapper.readTree(turn.getArtifactJson()).path("type").asText())) {
                    throw new BusinessException("模型本轮选择了答疑，请通过消息入口继续：" + turn.getAssistantMessage());
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new BusinessException("回合产物无法读取");
            }
        }
        return turn;
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
