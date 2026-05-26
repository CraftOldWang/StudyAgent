package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.AgentStepRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentStepRecordMapper extends BaseMapper<AgentStepRecord> {

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
