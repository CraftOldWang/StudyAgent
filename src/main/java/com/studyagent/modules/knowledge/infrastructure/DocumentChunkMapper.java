package com.studyagent.modules.knowledge.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.knowledge.domain.DocumentChunk;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    @Delete("DELETE FROM document_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    @Update("""
            UPDATE document_chunks
            SET es_doc_id = #{esDocId}
            WHERE id = #{id}
              AND es_doc_id IS NULL
            """)
    int updateEsDocIdIfMissing(@Param("id") Long id, @Param("esDocId") String esDocId);

    @Select("""
            SELECT COUNT(*)
            FROM document_chunks
            WHERE document_id = #{documentId}
              AND es_doc_id IS NULL
            """)
    int countMissingEsDocIdByDocumentId(@Param("documentId") Long documentId);

    @Select("""
            SELECT COUNT(*)
            FROM document_chunks
            WHERE document_id = #{documentId}
            """)
    int countByDocumentId(@Param("documentId") Long documentId);

    @Select("""
            SELECT *
            FROM document_chunks
            WHERE document_id = #{documentId}
              AND es_doc_id IS NULL
            ORDER BY chunk_index ASC
            """)
    List<DocumentChunk> selectMissingEsDocIdByDocumentId(@Param("documentId") Long documentId);

    @Select("""
            <script>
            SELECT *
            FROM document_chunks
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    List<DocumentChunk> selectChunksByIds(@Param("ids") Collection<Long> ids);

    @Select("""
            SELECT *
            FROM document_chunks
            WHERE document_id = #{documentId}
              AND chunk_index BETWEEN #{startIndex} AND #{endIndex}
            ORDER BY chunk_index ASC
            """)
    List<DocumentChunk> selectWindow(
            @Param("documentId") Long documentId,
            @Param("startIndex") int startIndex,
            @Param("endIndex") int endIndex
    );
}
