package com.studyagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.KnowledgePointMapper;
import com.studyagent.mapper.LearningPlanMapper;
import com.studyagent.mapper.LearningSessionMapper;
import com.studyagent.mapper.QuizMapper;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningPlan;
import com.studyagent.model.LearningSession;
import com.studyagent.model.Quiz;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningPersistenceService {

    private final LearningSessionMapper sessionMapper;
    private final LearningPlanMapper planMapper;
    private final KnowledgePointMapper knowledgePointMapper;
    private final QuizMapper quizMapper;
    private final ObjectMapper objectMapper;
    private final KnowledgePointLifecycle lifecycle = new KnowledgePointLifecycle();

    @Transactional
    public LearningSession create(
            Long userId,
            Long knowledgeBaseId,
            String learningGoal,
            String agentScopeSessionId,
            List<LearningPlanItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException("学习计划不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        LearningSession session = new LearningSession();
        session.setUserId(userId);
        session.setKnowledgeBaseId(knowledgeBaseId);
        session.setLearningGoal(learningGoal);
        session.setAgentscopeSessionId(agentScopeSessionId);
        session.setStatus("ACTIVE");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);

        LearningPlan plan = new LearningPlan();
        plan.setSessionId(session.getId());
        plan.setUserId(userId);
        plan.setPlanJson(toJson(items));
        plan.setCreatedAt(now);
        planMapper.insert(plan);

        for (int index = 0; index < items.size(); index++) {
            LearningPlanItem item = items.get(index);
            KnowledgePoint point = new KnowledgePoint();
            point.setSessionId(session.getId());
            point.setUserId(userId);
            point.setSequenceNo(index + 1);
            point.setTopic(item.topic());
            point.setSubtopicsJson(toJson(item.subtopics()));
            point.setEstimatedMinutes(item.estimatedMinutes());
            point.setStatus(KnowledgePointStatus.NEW.name());
            point.setCreatedAt(now);
            point.setUpdatedAt(now);
            knowledgePointMapper.insert(point);
            if (index == 0) {
                session.setActiveKnowledgePointId(point.getId());
            }
        }
        sessionMapper.updateById(session);
        return session;
    }

    public LearningSession requireSession(Long userId, Long sessionId) {
        LearningSession session = sessionMapper.selectOne(new LambdaQueryWrapper<LearningSession>()
                .eq(LearningSession::getId, sessionId)
                .eq(LearningSession::getUserId, userId));
        if (session == null) {
            throw new BusinessException(404, "学习会话不存在: " + sessionId);
        }
        return session;
    }

    public KnowledgePoint requireActivePoint(LearningSession session) {
        if (session.getActiveKnowledgePointId() == null) {
            throw new BusinessException("学习会话没有活跃知识点");
        }
        KnowledgePoint point = knowledgePointMapper.selectById(session.getActiveKnowledgePointId());
        if (point == null || !session.getId().equals(point.getSessionId())) {
            throw new BusinessException("学习会话的活跃知识点不存在");
        }
        return point;
    }

    public List<KnowledgePoint> listPoints(Long sessionId) {
        return knowledgePointMapper.selectList(new LambdaQueryWrapper<KnowledgePoint>()
                .eq(KnowledgePoint::getSessionId, sessionId)
                .orderByAsc(KnowledgePoint::getSequenceNo));
    }

    @Transactional
    public KnowledgePoint saveExplanationAndAdvance(
            LearningSession session, KnowledgePoint point, String explanation) {
        requireStatus(point, KnowledgePointStatus.NEW);
        point.setExplanation(explanation);
        KnowledgePoint advanced = advance(point, KnowledgePointStatus.EXPLAINING);
        clearSessionFailure(session);
        return advanced;
    }

    @Transactional
    public Quiz saveQuizAndAdvance(LearningSession session, KnowledgePoint point, String questionsJson) {
        requireStatus(point, KnowledgePointStatus.EXPLAINING);
        Quiz quiz = new Quiz();
        quiz.setUserId(point.getUserId());
        quiz.setSessionId(point.getSessionId());
        quiz.setKnowledgePointId(point.getId());
        quiz.setQuestionsJson(questionsJson);
        quiz.setCreatedAt(LocalDateTime.now());
        quizMapper.insert(quiz);
        advance(point, KnowledgePointStatus.QUIZZING);
        clearSessionFailure(session);
        return quiz;
    }

    public Quiz requireQuiz(KnowledgePoint point) {
        Quiz quiz = quizMapper.selectOne(new LambdaQueryWrapper<Quiz>()
                .eq(Quiz::getKnowledgePointId, point.getId()));
        if (quiz == null) {
            throw new BusinessException("当前知识点尚未生成测验");
        }
        return quiz;
    }

    @Transactional
    public void saveQuizResultAndAdvance(
            LearningSession session,
            Quiz quiz,
            KnowledgePoint point,
            String answersJson,
            int score,
            String feedbackJson) {
        requireStatus(point, KnowledgePointStatus.QUIZZING);
        quiz.setAnswersJson(answersJson);
        quiz.setScore(score);
        quiz.setFeedbackJson(feedbackJson);
        quiz.setAnsweredAt(LocalDateTime.now());
        quizMapper.updateById(quiz);
        advance(point, KnowledgePointStatus.CARD_GENERATING);
        clearSessionFailure(session);
    }

    @Transactional
    public void completePoint(LearningSession session, KnowledgePoint point) {
        requireStatus(point, KnowledgePointStatus.CARD_GENERATING);
        advance(point, KnowledgePointStatus.COMPLETED);
        List<KnowledgePoint> points = listPoints(session.getId());
        KnowledgePoint next = points.stream()
                .filter(candidate -> candidate.getSequenceNo() > point.getSequenceNo())
                .findFirst()
                .orElse(null);
        session.setActiveKnowledgePointId(next == null ? null : next.getId());
        session.setStatus(next == null ? "COMPLETED" : "ACTIVE");
        session.setErrorMessage(null);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    @Transactional
    public void recordFailure(LearningSession session, KnowledgePoint point, String message) {
        String safeMessage = message == null || message.isBlank() ? "未知失败" : message;
        LocalDateTime now = LocalDateTime.now();
        sessionMapper.update(null, new UpdateWrapper<LearningSession>()
                .eq("id", session.getId())
                .eq("user_id", session.getUserId())
                .set("error_message", safeMessage)
                .set("updated_at", now));
        if (point != null) {
            knowledgePointMapper.update(null, new UpdateWrapper<KnowledgePoint>()
                    .eq("id", point.getId())
                    .eq("session_id", session.getId())
                    .set("error_message", safeMessage)
                    .set("updated_at", now));
        }
    }

    @Transactional
    public void clearFailure(LearningSession session, KnowledgePoint point) {
        clearSessionFailure(session);
        if (point.getErrorMessage() != null) {
            point.setErrorMessage(null);
            point.setUpdatedAt(LocalDateTime.now());
            knowledgePointMapper.updateById(point);
        }
    }

    private KnowledgePoint advance(KnowledgePoint point, KnowledgePointStatus target) {
        KnowledgePointStatus current;
        try {
            current = KnowledgePointStatus.valueOf(point.getStatus());
        } catch (RuntimeException ex) {
            throw new BusinessException("未知知识点状态: " + point.getStatus());
        }
        lifecycle.advance(current, target);
        point.setStatus(target.name());
        point.setErrorMessage(null);
        point.setUpdatedAt(LocalDateTime.now());
        if (target == KnowledgePointStatus.EXPLAINING && point.getStartedAt() == null) {
            point.setStartedAt(LocalDateTime.now());
        }
        if (target == KnowledgePointStatus.COMPLETED) {
            point.setCompletedAt(LocalDateTime.now());
        }
        knowledgePointMapper.updateById(point);
        return point;
    }

    private void requireStatus(KnowledgePoint point, KnowledgePointStatus expected) {
        if (point == null || !expected.name().equals(point.getStatus())) {
            throw new BusinessException("当前知识点必须处于 " + expected + " 状态");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("学习业务数据序列化失败: " + ex.getMessage());
        }
    }

    private void clearSessionFailure(LearningSession session) {
        if (session.getErrorMessage() != null) {
            session.setErrorMessage(null);
            session.setUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
        }
    }
}
