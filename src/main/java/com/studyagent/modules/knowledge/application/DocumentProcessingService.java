package com.studyagent.modules.knowledge.application;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.objectstorage.ObjectStorageService;
import com.studyagent.infrastructure.parser.DocumentTextParser;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
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

/**
 * 文档处理应用服务，编排“对象读取 -> 文本解析 -> 切块 -> 向量化索引”的完整链路。
 *
 * <p>本服务不吞掉异常：失败会写入明确文档状态后继续抛出，让消息消费侧决定重试或进入失败流程。</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final FileRecordMapper fileRecordMapper;
    private final ObjectStorageService objectStorageService;
    private final DocumentTextParser documentTextParser;
    private final TextChunker textChunker;
    private final ElasticsearchChunkIndexer elasticsearchChunkIndexer;
    private final DocumentStatusService documentStatusService;
    private final DocumentChunkIndexSyncService documentChunkIndexSyncService;

    /**
     * 处理指定文档，已索引文档会幂等跳过。
     */
    public void process(Long documentId) {
        boolean indexingStarted = false;
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
            // 重跑文档处理时先清理旧 chunk 和旧 ES 索引，保证 MySQL 与 ES 能重新对齐。
            documentChunkMapper.deleteByDocumentId(document.getId());
            elasticsearchChunkIndexer.deleteByDocumentId(document.getId());
            updateStatus(document, "PARSING", "PENDING", null);
            String text = parseFile(fileRecord);

            updateStatus(document, "PARSED", "CHUNKING", null);
            List<String> chunks = textChunker.chunk(text);
            if (chunks.isEmpty()) {
                throw new BusinessException("文档切块结果为空");
            }

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
                    // 每三个子 chunk 选一个作为父 chunk，检索时可用它补全文档上下文窗口。
                    currentParentChunkId = chunk.getId();
                    chunk.setParentChunkId(currentParentChunkId);
                    documentChunkMapper.updateById(chunk);
                }
            }

            updateStatus(document, "PARSED", "INDEXING", null);
            indexingStarted = true;
            for (DocumentChunk chunk : documentChunkMapper.selectMissingEsDocIdByDocumentId(document.getId())) {
                // 每个 chunk 独立写 ES 并回填 es_doc_id，便于失败后从缺失点继续同步。
                documentChunkIndexSyncService.syncChunk(chunk.getId());
            }
            documentChunkIndexSyncService.markDocumentIndexedIfComplete(document.getId());
        } catch (Exception ex) {
            if (indexingStarted) {
                documentStatusService.markIndexFailed(document.getId(), ex.getMessage());
            } else {
                documentStatusService.markFailed(document.getId(), ex.getMessage());
            }
            throw ex;
        }
    }

    /**
     * 更新文档处理状态，状态字段用于前端展示和异步任务恢复。
     */
    private void updateStatus(Document document, String parseStatus, String indexStatus, String errorMessage) {
        document.setParseStatus(parseStatus);
        document.setIndexStatus(indexStatus);
        document.setErrorMessage(errorMessage);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
    }

    /**
     * 从对象存储读取文件内容并交给解析器提取文本。
     */
    private String parseFile(FileRecord fileRecord) {
        try (InputStream inputStream = objectStorageService.getObject(fileRecord.getObjectKey())) {
            return documentTextParser.parse(inputStream);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("读取或关闭文档对象失败: " + ex.getMessage());
        }
    }

    /**
     * 粗略估算 token 数，当前用于记录 chunk 成本和后续上下文预算。
     */
    private int estimateTokenCount(String content) {
        return Math.max(1, content.length() / 2);
    }

    /**
     * 构造 chunk 元数据 JSON，后续写入 ES 后可作为引用信息的一部分。
     */
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
