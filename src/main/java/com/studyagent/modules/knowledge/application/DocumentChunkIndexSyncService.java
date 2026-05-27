package com.studyagent.modules.knowledge.application;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
import com.studyagent.infrastructure.search.IndexedChunk;
import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.domain.DocumentChunk;
import com.studyagent.modules.knowledge.infrastructure.DocumentChunkMapper;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentChunkIndexSyncService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final EmbeddingService embeddingService;
    private final ElasticsearchChunkIndexer elasticsearchChunkIndexer;

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

        float[] embedding = embeddingService.embed(chunk.getContent());
        String esDocId = elasticsearchChunkIndexer.index(new IndexedChunk(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getKnowledgeBaseId(),
                chunk.getUserId(),
                chunk.getParentChunkId(),
                chunk.getChunkIndex(),
                document.getTitle(),
                chunk.getContent(),
                chunk.getMetadataJson(),
                embedding
        ));
        documentChunkMapper.updateEsDocIdIfMissing(chunk.getId(), esDocId);
        markDocumentIndexedIfComplete(document.getId());
        return true;
    }

    public void syncMissingChunks(Long documentId) {
        for (DocumentChunk chunk : documentChunkMapper.selectMissingEsDocIdByDocumentId(documentId)) {
            syncChunk(chunk.getId());
        }
    }

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

    private boolean isIndexSyncAllowed(Document document) {
        return "PARSED".equals(document.getParseStatus())
                && ("INDEXING".equals(document.getIndexStatus()) || "FAILED".equals(document.getIndexStatus()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
