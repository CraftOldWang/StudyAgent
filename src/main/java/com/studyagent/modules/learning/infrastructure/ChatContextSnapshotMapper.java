package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.ChatContextSnapshot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 上下文快照 Mapper，提供最近快照查询。
 */
public interface ChatContextSnapshotMapper extends BaseMapper<ChatContextSnapshot> {

    /**
     * 查询会话最近一次压缩快照。
     */
    @Select("""
            SELECT *
            FROM chat_context_snapshots
            WHERE session_id = #{sessionId}
            ORDER BY id DESC
            LIMIT 1
            """)
    ChatContextSnapshot selectLatest(@Param("sessionId") Long sessionId);
}
