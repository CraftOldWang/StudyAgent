package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.AgentStepRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Agent 阶段记录 Mapper，提供阶段恢复所需查询。
 */
public interface AgentStepRecordMapper extends BaseMapper<AgentStepRecord> {

    /**
     * 查询指定阶段最近一次成功记录。
     */
    @Select("""
            SELECT *
            FROM agent_step_records
            WHERE agent_run_id = #{agentRunId}
              AND stage = #{stage}
              AND status = 'COMPLETED'
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentStepRecord selectLatestCompleted(
            @Param("agentRunId") Long agentRunId,
            @Param("stage") String stage
    );
}
