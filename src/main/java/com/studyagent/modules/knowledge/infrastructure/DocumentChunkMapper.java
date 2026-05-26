package com.studyagent.modules.knowledge.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.knowledge.domain.DocumentChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    @Delete("DELETE FROM document_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}
