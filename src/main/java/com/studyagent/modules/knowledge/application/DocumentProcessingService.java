package com.studyagent.modules.knowledge.application;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.infrastructure.objectstorage.ObjectStorageService;
import com.studyagent.infrastructure.parser.DocumentTextParser;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
import com.studyagent.infrastructure.search.IndexedChunk;
import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.domain.DocumentChunk;
import com.studyagent.modules.knowledge.infrastructure.DocumentChunkMapper;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import com.studyagent.modules.storage.domain.FileRecord;
import com.studyagent.modules.storage.infrastructure.FileRecordMapper;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final FileRecordMapper fileRecordMapper;
    private final ObjectStorageService objectStorageService;
    private final DocumentTextParser documentTextParser;
    private final TextChunker textChunker;
    private final EmbeddingService embeddingService;
    private final ElasticsearchChunkIndexer elasticsearchChunkIndexer;
    private final DocumentStatusService documentStatusService;

    @Transactional
    public void process(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException("文档不存在: " + documentId);
        }
        if ("INDEXED".equals(document.getIndexStatus())) {
            return;
        }
        FileRecord fileRecord = fileRecordMapper.selectById(document.getFileId());
        if (fileRecord == null) {
            documentStatusService.markFailed(document.getId(), "文件记录不存在: " + document.getFileId());
            throw new BusinessException("文件记录不存在: " + document.getFileId());
        }

        try {
            documentChunkMapper.deleteByDocumentId(document.getId());
            elasticsearchChunkIndexer.deleteByDocumentId(document.getId());
            updateStatus(document, "PARSING", "PENDING", null);
            String text = parseFile(fileRecord);

            updateStatus(document, "PARSED", "CHUNKING", null);
            List<String> chunks = textChunker.chunk(text);
            if (chunks.isEmpty()) {
                throw new BusinessException("文档切块结果为空");
            }

            updateStatus(document, "PARSED", "INDEXING", null);
            Long currentParentChunkId = null;
            for (int i = 0; i < chunks.size(); i++) {
                String content = chunks.get(i);
                DocumentChunk chunk = new DocumentChunk();
                chunk.setDocumentId(document.getId());
                chunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
                chunk.setUserId(document.getUserId());
                chunk.setParentChunkId(currentParentChunkId);
                chunk.setChunkIndex(i);
                chunk.setContent(content);
                chunk.setTokenCount(estimateTokenCount(content));
                chunk.setMetadataJson(chunkMetadata(document, i, chunks.size()));
                chunk.setCreatedAt(LocalDateTime.now());
                documentChunkMapper.insert(chunk);
                if (i % 3 == 0) {
                    currentParentChunkId = chunk.getId();
                    chunk.setParentChunkId(currentParentChunkId);
                }

                float[] embedding = embeddingService.embed(content);
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
                chunk.setEsDocId(esDocId);
                documentChunkMapper.updateById(chunk);
            }

            updateStatus(document, "PARSED", "INDEXED", null);
        } catch (Exception ex) {
            documentStatusService.markFailed(document.getId(), ex.getMessage());
            throw ex;
        }
    }

    private void updateStatus(Document document, String parseStatus, String indexStatus, String errorMessage) {
        document.setParseStatus(parseStatus);
        document.setIndexStatus(indexStatus);
        document.setErrorMessage(errorMessage);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
    }

    private String parseFile(FileRecord fileRecord) {
        try (InputStream inputStream = objectStorageService.getObject(fileRecord.getObjectKey())) {
            return documentTextParser.parse(inputStream);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("读取或关闭文档对象失败: " + ex.getMessage());
        }
    }

    private int estimateTokenCount(String content) {
        return Math.max(1, content.length() / 2);
    }

    private String chunkMetadata(Document document, int chunkIndex, int totalChunks) {
        return """
                {"documentTitle":"%s","sourceType":"%s","chunkIndex":%d,"totalChunks":%d}
                """.formatted(
                escapeJson(document.getTitle()),
                escapeJson(document.getSourceType()),
                chunkIndex,
                totalChunks
        ).trim();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
