package com.studyagent.modules.storage.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.storage.domain.UploadSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分片上传会话 Mapper，封装会话恢复所需的查询。
 */
public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    /**
     * 查询同一用户、知识库和文件哈希下仍可继续上传的最新会话。
     */
    @Select("""
            SELECT *
            FROM upload_sessions
            WHERE user_id = #{userId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND file_hash = #{fileHash}
              AND status = 'UPLOADING'
              AND expires_at > NOW()
            ORDER BY created_at DESC
            LIMIT 1
            """)
    UploadSession selectActiveSession(
            @Param("userId") Long userId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("fileHash") String fileHash
    );
}
