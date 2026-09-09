package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.model.Document;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DocumentMapper extends BaseMapper<Document> {
    // Background recovery reads only queue routing metadata, then execution re-enters the user's scope.
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, user_id FROM documents
            WHERE pipeline_status = 'STORED'
               OR (pipeline_status IN ('PARSING','TRANSCRIBING','TRANSCRIBED','PARSED','CHUNKING','CHUNKED','EMBEDDING','EMBEDDED','INDEXING')
                   AND (lease_until IS NULL OR lease_until <= #{now}))
            ORDER BY updated_at, id LIMIT #{limit}
            """)
    List<Document> findRecoveryCandidates(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
