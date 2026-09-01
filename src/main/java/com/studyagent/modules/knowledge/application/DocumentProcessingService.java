package com.studyagent.modules.knowledge.application;

import com.studyagent.algo.chunk.TextChunker;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.RagProperties;
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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 文档处理应用服务，编排“对象读取 -> 文本解析 -> 切块 -> 向量化索引”的完整链路。
 *
 * <p>本服务不吞掉异常：失败会写入明确文档状态后继续抛出，让消息消费侧决定重试或进入失败流程。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessingService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final FileRecordMapper fileRecordMapper;
    private final ObjectStorageService objectStorageService;
    private final DocumentTextParser documentTextParser;
    private final RagProperties ragProperties;
    private final ElasticsearchChunkIndexer elasticsearchChunkIndexer;
    private final DocumentStatusService documentStatusService;
    private final DocumentChunkIndexSyncService documentChunkIndexSyncService;

    /**
     * 处理指定文档，已索引文档会幂等跳过。
     */
    public void process(Long documentId) {
        long startedAt = System.nanoTime();
        boolean indexingStarted = false;
        log.info("文档处理开始: documentId={}", documentId);
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            log.warn("文档处理失败: 文档不存在, documentId={}", documentId);
            throw new BusinessException("文档不存在: " + documentId);
        }
        if ("INDEXED".equals(document.getIndexStatus())) {
            log.info("文档已索引，跳过重复处理: documentId={}", documentId);
            return;
        }
        FileRecord fileRecord = fileRecordMapper.selectById(document.getFileId());
        if (fileRecord == null) {
            documentStatusService.markFailed(document.getId(), "文件记录不存在: " + document.getFileId());
            log.warn("文档处理失败: 文件记录不存在, documentId={}, fileId={}", document.getId(), document.getFileId());
            throw new BusinessException("文件记录不存在: " + document.getFileId());
        }

        try {
            log.info(
                    "文档处理载入文件记录: documentId={}, fileId={}, bucket={}, objectKey={}, filename={}, size={}",
                    document.getId(),
                    fileRecord.getId(),
                    fileRecord.getBucket(),
                    fileRecord.getObjectKey(),
                    fileRecord.getFilename(),
                    fileRecord.getSize()
            );
            // 重跑文档处理时先清理旧 chunk 和旧 ES 索引，保证 MySQL 与 ES 能重新对齐。
            documentChunkMapper.deleteByDocumentId(document.getId());
            elasticsearchChunkIndexer.deleteByDocumentId(document.getId());
            log.info("文档处理已清理旧索引数据: documentId={}", document.getId());

            updateStatus(document, "PARSING", "PENDING", null);
            long parseStartedAt = System.nanoTime();
            String text = parseFile(fileRecord);
            log.info(
                    "文档解析完成: documentId={}, textLength={}, parseMillis={}",
                    document.getId(),
                    text.length(),
                    elapsedMillis(parseStartedAt)
            );

            updateStatus(document, "PARSED", "CHUNKING", null);
            long chunkStartedAt = System.nanoTime();
            List<ChunkDraft> chunkDrafts = buildParentChildChunks(text);
            if (chunkDrafts.isEmpty()) {
                throw new BusinessException("文档切块结果为空");
            }
            int childChunkCount = chunkDrafts.stream().mapToInt(draft -> draft.childContents().size()).sum();
            log.info(
                    "文档切块完成: documentId={}, parentChunks={}, childChunks={}, chunkMillis={}",
                    document.getId(),
                    chunkDrafts.size(),
                    childChunkCount,
                    elapsedMillis(chunkStartedAt)
            );

            long chunkInsertStartedAt = System.nanoTime();
            for (ChunkDraft draft : chunkDrafts) {
                DocumentChunk parentChunk = createChunk(
                        document,
                        null,
                        DocumentChunk.TYPE_PARENT,
                        draft.parentIndex(),
                        draft.parentContent(),
                        parentMetadata(document, draft.parentIndex(), chunkDrafts.size())
                );
                documentChunkMapper.insert(parentChunk);
                for (int childOffset = 0; childOffset < draft.childContents().size(); childOffset++) {
                    int childIndex = draft.globalChildStartIndex() + childOffset;
                    DocumentChunk childChunk = createChunk(
                            document,
                            parentChunk.getId(),
                            DocumentChunk.TYPE_CHILD,
                            childIndex,
                            draft.childContents().get(childOffset),
                            childMetadata(document, draft.parentIndex(), childOffset, childIndex)
                    );
                    documentChunkMapper.insert(childChunk);
                }
            }
            log.info(
                    "文档 chunk 入库完成: documentId={}, parentChunks={}, childChunks={}, insertMillis={}",
                    document.getId(),
                    chunkDrafts.size(),
                    childChunkCount,
                    elapsedMillis(chunkInsertStartedAt)
            );

            updateStatus(document, "PARSED", "INDEXING", null);
            indexingStarted = true;
            List<DocumentChunk> chunksToIndex = documentChunkMapper.selectMissingEsDocIdByDocumentId(document.getId());
            log.info("文档 ES/embedding 同步开始: documentId={}, chunksToIndex={}", document.getId(), chunksToIndex.size());
            long indexStartedAt = System.nanoTime();
            for (int index = 0; index < chunksToIndex.size(); index++) {
                DocumentChunk chunk = chunksToIndex.get(index);
                // 每个 chunk 独立写 ES 并回填 es_doc_id，便于失败后从缺失点继续同步。
                documentChunkIndexSyncService.syncChunk(chunk.getId());
                if (shouldLogIndexProgress(index, chunksToIndex.size())) {
                    log.info(
                            "文档 ES/embedding 同步进度: documentId={}, finishedChunks={}/{}",
                            document.getId(),
                            index + 1,
                            chunksToIndex.size()
                    );
                }
            }
            documentChunkIndexSyncService.markDocumentIndexedIfComplete(document.getId());
            log.info(
                    "文档处理完成: documentId={}, indexedChunks={}, totalMillis={}, indexMillis={}",
                    document.getId(),
                    chunksToIndex.size(),
                    elapsedMillis(startedAt),
                    elapsedMillis(indexStartedAt)
            );
        } catch (Exception ex) {
            if (indexingStarted) {
                documentStatusService.markIndexFailed(document.getId(), ex.getMessage());
            } else {
                documentStatusService.markFailed(document.getId(), ex.getMessage());
            }
            log.error(
                    "文档处理失败: documentId={}, indexingStarted={}, totalMillis={}",
                    document.getId(),
                    indexingStarted,
                    elapsedMillis(startedAt),
                    ex
            );
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
        log.info(
                "文档状态更新: documentId={}, parseStatus={}, indexStatus={}, error={}",
                document.getId(),
                parseStatus,
                indexStatus,
                errorMessage
        );
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
     * 构造真正的父子 chunk 草稿。
     *
     * <p>父块先按较大窗口覆盖原文，子块再在父块内部按较小窗口切分。检索阶段只召回子块，
     * 命中后通过 parent_chunk_id 回到父块补全上下文；这比“命中块前后各取 N 个”更稳定，
     * 因为父块边界在入库时已经确定，不依赖检索时临时猜测相邻片段是否相关。</p>
     */
    private List<ChunkDraft> buildParentChildChunks(String text) {
        List<String> parentContents = TextChunker.parentChunks(
                text,
                ragProperties.parentChunkSize(),
                ragProperties.parentChunkOverlap()
        );
        List<ChunkDraft> drafts = new ArrayList<>();
        int globalChildIndex = 0;
        for (int parentIndex = 0; parentIndex < parentContents.size(); parentIndex++) {
            String parentContent = parentContents.get(parentIndex);
            List<String> childContents = TextChunker.chunk(
                    parentContent,
                    ragProperties.chunkSize(),
                    ragProperties.chunkOverlap()
            );
            if (childContents.isEmpty()) {
                continue;
            }
            drafts.add(new ChunkDraft(parentIndex, parentContent, globalChildIndex, childContents));
            globalChildIndex += childContents.size();
        }
        return drafts;
    }

    /**
     * 创建待入库 chunk。父块 parentChunkId 为空；子块 parentChunkId 指向父块主键。
     */
    private DocumentChunk createChunk(
            Document document,
            Long parentChunkId,
            String chunkType,
            int chunkIndex,
            String content,
            String metadataJson
    ) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocumentId(document.getId());
        chunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
        chunk.setUserId(document.getUserId());
        chunk.setParentChunkId(parentChunkId);
        chunk.setChunkType(chunkType);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        chunk.setTokenCount(estimateTokenCount(content));
        chunk.setMetadataJson(metadataJson);
        chunk.setCreatedAt(LocalDateTime.now());
        return chunk;
    }

    /**
     * 粗略估算 token 数，当前用于记录 chunk 成本和后续上下文预算。
     */
    private int estimateTokenCount(String content) {
        return Math.max(1, content.length() / 2);
    }

    /**
     * 构造父 chunk 元数据 JSON，后续可用于定位父段在原文中的大致位置。
     */
    private String parentMetadata(Document document, int parentIndex, int totalParents) {
        return """
                {"documentTitle":"%s","sourceType":"%s","chunkType":"PARENT","parentIndex":%d,"totalParents":%d}
                """.formatted(
                escapeJson(document.getTitle()),
                escapeJson(document.getSourceType()),
                parentIndex,
                totalParents
        ).trim();
    }

    /**
     * 构造子 chunk 元数据 JSON，记录父块序号和全局子块序号，方便排查召回命中点。
     */
    private String childMetadata(Document document, int parentIndex, int localChildIndex, int globalChildIndex) {
        return """
                {"documentTitle":"%s","sourceType":"%s","chunkType":"CHILD","parentIndex":%d,"localChildIndex":%d,"chunkIndex":%d}
                """.formatted(
                escapeJson(document.getTitle()),
                escapeJson(document.getSourceType()),
                parentIndex,
                localChildIndex,
                globalChildIndex
        ).trim();
    }

    private record ChunkDraft(
            int parentIndex,
            String parentContent,
            int globalChildStartIndex,
            List<String> childContents
    ) {
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean shouldLogIndexProgress(int chunkIndex, int totalChunks) {
        return chunkIndex == 0 || chunkIndex == totalChunks - 1 || (chunkIndex + 1) % 10 == 0;
    }

    private long elapsedMillis(long startedAtNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
