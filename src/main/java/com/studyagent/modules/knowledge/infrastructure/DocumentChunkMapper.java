package com.studyagent.modules.knowledge.infrastructure;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.modules.knowledge.domain.DocumentChunk;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 文档 chunk Mapper，提供索引同步和父子检索所需的查询。
 */
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 删除文档下所有 chunk，用于文档重新处理前清理旧数据。
     */
    @Delete("DELETE FROM document_chunks WHERE document_id = #{documentId}")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 只在 es_doc_id 仍为空时回填，避免并发重试覆盖已有同步结果。
     */
    @Update("""
            UPDATE document_chunks
            SET es_doc_id = #{esDocId}
            WHERE id = #{id}
              AND es_doc_id IS NULL
            """)
    int updateEsDocIdIfMissing(@Param("id") Long id, @Param("esDocId") String esDocId);

    /**
     * 统计尚未同步到 ES 的 chunk 数。
     *
     * <p>父子检索要求 PARENT 和 CHILD 都进入 ES，因此完成判断必须覆盖两类 chunk。</p>
     */
    @Select("""
            SELECT COUNT(*)
            FROM document_chunks
            WHERE document_id = #{documentId}
              AND es_doc_id IS NULL
            """)
    int countMissingEsDocIdByDocumentId(@Param("documentId") Long documentId);

    /**
     * 统计文档总 chunk 数，包含父块和子块。
     */
    @Select("""
            SELECT COUNT(*)
            FROM document_chunks
            WHERE document_id = #{documentId}
            """)
    int countByDocumentId(@Param("documentId") Long documentId);

    /**
     * 查询尚未回填 es_doc_id 的 chunk。
     *
     * <p>按主键顺序返回可以基本保持“父块先插入、子块随后插入”的索引顺序，便于调试 ES 中的父子关系。</p>
     */
    @Select("""
            SELECT *
            FROM document_chunks
            WHERE document_id = #{documentId}
              AND es_doc_id IS NULL
            ORDER BY id ASC
            """)
    List<DocumentChunk> selectMissingEsDocIdByDocumentId(@Param("documentId") Long documentId);

    /**
     * 批量查询指定 chunk，用于检索结果引用回填。
     */
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
}
