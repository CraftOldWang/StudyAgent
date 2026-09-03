package com.studyagent.modules.knowledge.application;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.domain.DocumentChunk;
import com.studyagent.modules.knowledge.infrastructure.DocumentChunkMapper;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import com.studyagent.rag.index.ElasticsearchIndexer;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档 chunk 索引同步服务，负责向量生成、写入 Elasticsearch、回填 es_doc_id。
 *
 * <p>每个 chunk 使用独立事务处理，保证部分成功时也能持久化进度，后续只重试缺失的 chunk。</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentChunkIndexSyncService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final EmbeddingService embeddingService;
    private final ElasticsearchIndexer elasticsearchIndexer;

    /**
     * 同步单个 chunk 到 ES。已同步或当前文档状态不允许索引时返回 false。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean syncChunk(Long chunkId) {
        DocumentChunk chunk = documentChunkMapper.selectById(chunkId);
        if (chunk == null || hasText(chunk.getEsDocId())) {
            return false;
        }
        Document document = documentMapper.selectById(chunk.getDocumentId());
        if (document == null) {
            throw new BusinessException("文档不存在: " + chunk.getDocumentId());
        }
        if (!isIndexSyncAllowed(document)) {
            return false;
        }

        // 父块和子块都写入 ES：子块用于召回，父块用于通过 parent_chunk_id 补全上下文。
        // 这里为父块也生成 embedding，是为了复用同一个 ES mapping；查询阶段会显式过滤 CHILD，避免父块参与召回排序。
        float[] embedding = embeddingService.embed(chunk.getContent());
        String esDocId = elasticsearchIndexer.index(new ElasticsearchChunkDocument(
                chunk.getUserId() == null ? null : String.valueOf(chunk.getUserId()),
                String.valueOf(chunk.getKnowledgeBaseId()),
                String.valueOf(chunk.getDocumentId()),
                String.valueOf(chunk.getId()),
                chunk.getParentChunkId() == null ? null : String.valueOf(chunk.getParentChunkId()),
                chunk.getChunkType(),
                chunk.getChunkIndex(),
                chunk.getContent(),
                null,
                embedding,
                null,
                null,
                chunk.getCreatedAt()
        ));
        // 条件回填避免并发重试覆盖已经写入的 ES 文档 ID。
        documentChunkMapper.updateEsDocIdIfMissing(chunk.getId(), esDocId);
        markDocumentIndexedIfComplete(document.getId());
        return true;
    }

    /**
     * 只同步指定文档中尚未回填 es_doc_id 的 chunk。
     */
    public void syncMissingChunks(Long documentId) {
        for (DocumentChunk chunk : documentChunkMapper.selectMissingEsDocIdByDocumentId(documentId)) {
            syncChunk(chunk.getId());
        }
    }

    /**
     * 当文档所有 chunk 都完成 ES 回填后，将文档状态推进为 INDEXED。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDocumentIndexedIfComplete(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            return;
        }
        if (!isIndexSyncAllowed(document)) {
            return;
        }
        if (documentChunkMapper.countByDocumentId(documentId) == 0) {
            return;
        }
        if (documentChunkMapper.countMissingEsDocIdByDocumentId(documentId) > 0) {
            return;
        }
        document.setParseStatus("PARSED");
        document.setIndexStatus("INDEXED");
        document.setErrorMessage(null);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
    }

    /**
     * 只允许已解析且处于索引中或索引失败状态的文档继续同步。
     */
    private boolean isIndexSyncAllowed(Document document) {
        return "PARSED".equals(document.getParseStatus())
                && ("INDEXING".equals(document.getIndexStatus()) || "FAILED".equals(document.getIndexStatus()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
