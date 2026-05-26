package com.studyagent.modules.storage.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.storage.domain.UploadSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    @Select("""
            SELECT *
            FROM upload_sessions
            WHERE user_id = #{userId}
              AND knowledge_base_id = #{knowledgeBaseId}
              AND file_md5 = #{fileMd5}
              AND status = 'UPLOADING'
              AND expires_at > NOW()
            ORDER BY created_at DESC
            LIMIT 1
            """)
    UploadSession selectActiveSession(
            @Param("userId") Long userId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("fileMd5") String fileMd5
    );
}
