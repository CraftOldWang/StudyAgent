package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.AgentRun;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Agent Run Mapper，提供运行中会话查询。
 */
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    /**
     * 查询某会话下最新的 RUNNING Agent Run。
     */
    @Select("""
            SELECT *
            FROM agent_runs
            WHERE session_id = #{sessionId}
              AND user_id = #{userId}
              AND status = 'RUNNING'
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentRun selectRunningBySession(
            @Param("sessionId") Long sessionId,
            @Param("userId") Long userId
    );
}
