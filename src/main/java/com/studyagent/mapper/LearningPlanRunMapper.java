package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.model.LearningPlanRun;

public interface LearningPlanRunMapper extends BaseMapper<LearningPlanRun> {
    @org.apache.ibatis.annotations.Select("""
            SELECT id, knowledge_base_id, learning_goal, status, session_id, updated_at
            FROM learning_plan_runs WHERE user_id = #{userId} AND knowledge_base_id = #{knowledgeBaseId}
            ORDER BY updated_at DESC, id DESC
            """)
    java.util.List<com.studyagent.learning.LearningCatalog.PlanEntry> listCatalog(
            @org.apache.ibatis.annotations.Param("userId") Long userId,
            @org.apache.ibatis.annotations.Param("knowledgeBaseId") Long knowledgeBaseId);
}
