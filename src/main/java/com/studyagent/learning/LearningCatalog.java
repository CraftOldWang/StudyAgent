package com.studyagent.learning;

import com.studyagent.mapper.LearningPlanRunMapper;
import com.studyagent.mapper.LearningSessionMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LearningCatalog {
    private final LearningPlanRunMapper plans;
    private final LearningSessionMapper sessions;

    public List<PlanEntry> plans(Long userId, Long knowledgeBaseId) {
        return plans.listCatalog(userId, knowledgeBaseId);
    }

    public List<SessionEntry> sessions(Long userId, Long knowledgeBaseId) {
        return sessions.listCatalog(userId, knowledgeBaseId);
    }

    public record PlanEntry(Long id, Long knowledgeBaseId, String learningGoal, String status,
                            Long sessionId, LocalDateTime updatedAt) { }

    public record SessionEntry(Long id, Long knowledgeBaseId, String learningGoal, String status,
                               LocalDateTime updatedAt, Long planId, int completedPoints, int totalPoints) { }
}
