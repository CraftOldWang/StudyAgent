package com.studyagent.modules.learning.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.learning.domain.ChatMessage;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 会话消息 Mapper，支持按快照覆盖点加载增量消息。
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 查询指定 messageId 之后的会话消息。
     */
    @Select("""
            SELECT *
            FROM chat_messages
            WHERE session_id = #{sessionId}
              AND id > #{afterMessageId}
            ORDER BY id ASC
            """)
    List<ChatMessage> selectAfter(
            @Param("sessionId") Long sessionId,
            @Param("afterMessageId") Long afterMessageId
    );
}
