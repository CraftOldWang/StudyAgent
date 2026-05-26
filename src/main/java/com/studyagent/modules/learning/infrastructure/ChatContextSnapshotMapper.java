package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.ChatContextSnapshot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ChatContextSnapshotMapper extends BaseMapper<ChatContextSnapshot> {

    @Select("""
            SELECT *
            FROM chat_context_snapshots
            WHERE session_id = #{sessionId}
            ORDER BY id DESC
            LIMIT 1
            """)
    ChatContextSnapshot selectLatest(@Param("sessionId") Long sessionId);
}
