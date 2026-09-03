package com.studyagent.modules.evaluation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * DeepSeek 评测集生成应用服务。
 *
 * <p>服务先从 MySQL 读取真实切块，再抽样投喂 DeepSeek。这样生成的 expectedChunkIds 能直接用于
 * Elasticsearch 当前索引的 Recall 评测，不需要另建数据库表。</p>
 */
@Service
@RequiredArgsConstructor
public class DeepseekEvalCaseGenerationService {

    private static final int DEFAULT_CASE_COUNT = 10;
    private static final int DEFAULT_MAX_SOURCE_CHUNKS = 24;
    private static final int DEFAULT_MAX_CHUNK_CHARS = 900;
    private static final int MAX_SOURCE_CHUNKS = 80;
    private static final int MAX_CHUNK_CHARS = 1600;

    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentMapper documentMapper;
    private final DeepseekEvalCaseClient deepseekEvalCaseClient;

    /**
     * 生成临时评测集。
     */
    public GeneratedRagEvalDataset generate(RagEvalCaseGenerationRequest request) {
        Long userId = request.userId() == null ? KnowledgeBaseService.DEFAULT_USER_ID : request.userId();
        validateScope(request.knowledgeBaseIds());
        int caseCount = clamp(request.caseCount(), DEFAULT_CASE_COUNT, 1, 50);
        int maxSourceChunks = clamp(request.maxSourceChunks(), DEFAULT_MAX_SOURCE_CHUNKS, 1, MAX_SOURCE_CHUNKS);
        int maxChunkChars = clamp(request.maxChunkChars(), DEFAULT_MAX_CHUNK_CHARS, 100, MAX_CHUNK_CHARS);
        boolean indexedOnly = request.indexedOnly() == null || request.indexedOnly();

        List<Document> documents = loadCandidateDocuments(
                userId,
                request.knowledgeBaseIds(),
                request.documentIds(),
                indexedOnly
        );
        if (documents.isEmpty()) {
            throw new BusinessException("没有找到当前用户和知识库范围内的可用 documents");
        }
        List<DocumentChunk> chunks = loadCandidateChunks(
                documents.stream().map(Document::getId).toList(),
                indexedOnly,
                Math.max(maxSourceChunks * 4, maxSourceChunks)
        );
        if (chunks.isEmpty()) {
            throw new BusinessException("没有找到可用于生成评测集的 document_chunks，请先上传、切分并索引文档");
        }
        List<DocumentChunk> sampledChunks = sampleEvenly(chunks, maxSourceChunks);
        Map<Long, Document> documentsById = new HashMap<>();
        for (Document document : documents) {
            documentsById.put(document.getId(), document);
        }
        List<GeneratedRagEvalDataset.SourceChunk> sourceChunks = sampledChunks.stream()
                .map(chunk -> toSourceChunk(chunk, documentsById.get(chunk.getDocumentId()), maxChunkChars))
                .toList();
        return deepseekEvalCaseClient.generate(sourceChunks, caseCount);
    }

    /**
     * 加载候选 chunk。评测集默认只从已索引 chunk 生成，否则 Recall 会因为 ES 中缺数据而天然偏低。
     */
    private List<Document> loadCandidateDocuments(
            Long userId,
            List<Long> knowledgeBaseIds,
            List<Long> documentIds,
            boolean indexedOnly
    ) {
        LambdaQueryWrapper<Document> wrapper = new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .in(Document::getKnowledgeBaseId, knowledgeBaseIds)
                .orderByAsc(Document::getId);
        if (documentIds != null && !documentIds.isEmpty()) {
            wrapper.in(Document::getId, documentIds);
        }
        if (indexedOnly) {
            wrapper.eq(Document::getPipelineStatus, "COMPLETED");
        }
        return documentMapper.selectList(wrapper);
    }

    private List<DocumentChunk> loadCandidateChunks(
            List<Long> documentIds,
            boolean indexedOnly,
            int limit
    ) {
        LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<DocumentChunk>()
                .in(DocumentChunk::getDocumentId, documentIds)
                .eq(DocumentChunk::getChunkType, "CHILD")
                .orderByAsc(DocumentChunk::getDocumentId)
                .orderByAsc(DocumentChunk::getChunkIndex)
                .last("LIMIT " + limit);
        if (indexedOnly) {
            wrapper.isNotNull(DocumentChunk::getIndexedAt);
        }
        return documentChunkMapper.selectList(wrapper);
    }

    /**
     * 按原顺序做均匀抽样，避免只把文档开头塞给模型。
     */
    private List<DocumentChunk> sampleEvenly(List<DocumentChunk> chunks, int maxSourceChunks) {
        if (chunks.size() <= maxSourceChunks) {
            return chunks;
        }
        if (maxSourceChunks == 1) {
            return List.of(chunks.getFirst());
        }
        List<DocumentChunk> sampled = new ArrayList<>();
        double step = (chunks.size() - 1.0d) / (maxSourceChunks - 1.0d);
        for (int i = 0; i < maxSourceChunks; i++) {
            int index = (int) Math.round(i * step);
            sampled.add(chunks.get(Math.min(index, chunks.size() - 1)));
        }
        return sampled;
    }

    private GeneratedRagEvalDataset.SourceChunk toSourceChunk(
            DocumentChunk chunk,
            Document document,
            int maxChunkChars
    ) {
        if (document == null) {
            throw new BusinessException("chunk 关联的 document 不存在: " + chunk.getDocumentId());
        }
        return new GeneratedRagEvalDataset.SourceChunk(
                chunk.getChunkId(),
                chunk.getDocumentId(),
                document.getKnowledgeBaseId(),
                chunk.getParentChunkId(),
                chunk.getChunkType(),
                chunk.getChunkIndex(),
                document.getTitle(),
                truncate(chunk.getContent(), maxChunkChars)
        );
    }

    private void validateScope(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
            throw new BusinessException("知识库范围不能为空");
        }
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        int actual = value == null ? defaultValue : value;
        return Math.max(min, Math.min(max, actual));
    }

    private String truncate(String content, int maxChars) {
        if (content == null || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + "\n[内容已截断]";
    }
}
