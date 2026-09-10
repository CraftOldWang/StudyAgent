package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.model.LearningSession;

public interface LearningSessionMapper extends BaseMapper<LearningSession> {
    @org.apache.ibatis.annotations.Select("""
            SELECT s.id, s.knowledge_base_id, s.learning_goal, s.status, s.updated_at,
                   r.id AS plan_id,
                   (SELECT COUNT(*) FROM knowledge_points p WHERE p.session_id = s.id AND p.status = 'COMPLETED') AS completed_points,
                   (SELECT COUNT(*) FROM knowledge_points p WHERE p.session_id = s.id) AS total_points
            FROM learning_sessions s
            LEFT JOIN learning_plan_runs r ON r.session_id = s.id AND r.user_id = s.user_id
            WHERE s.user_id = #{userId} AND s.knowledge_base_id = #{knowledgeBaseId}
            ORDER BY s.updated_at DESC, s.id DESC
            """)
    java.util.List<com.studyagent.learning.LearningCatalog.SessionEntry> listCatalog(
            @org.apache.ibatis.annotations.Param("userId") Long userId,
            @org.apache.ibatis.annotations.Param("knowledgeBaseId") Long knowledgeBaseId);
}
