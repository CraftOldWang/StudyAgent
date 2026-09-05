package com.studyagent.rag.retrieval;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.RagProperties;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import com.studyagent.rag.embedding.EmbeddingService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * StudyAgent 的权限范围内混合检索入口，不依赖 AgentScope 已弃用的 rag package。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private final EmbeddingService embeddingService;
    private final RetrievalService retrievalService;
    private final RagProperties ragProperties;

    public KnowledgeSearchResponse search(Long userId, Long knowledgeBaseId, String query) {
        if (userId == null || knowledgeBaseId == null) {
            throw new BusinessException("检索 scope 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new BusinessException("检索问题不能为空");
        }
        String normalizedQuery = query.trim();
        float[] queryVector = embeddingService.embed(normalizedQuery, EmbeddingPurpose.QUERY);
        int candidateLimit = Math.max(
                ragProperties.bm25CandidateSize(), ragProperties.vectorCandidateSize());
        List<KnowledgeSearchResponse.Result> results = retrievalService.retrieve(
                        RetrievalMode.PARENT,
                        userId.toString(),
                        knowledgeBaseId.toString(),
                        normalizedQuery,
                        queryVector,
                        candidateLimit,
                        ragProperties.topK()).stream()
                .map(hit -> new KnowledgeSearchResponse.Result(
                        hit.chunkId(), hit.content(), hit.provenance(), hit.score()))
                .toList();
        return new KnowledgeSearchResponse(
                normalizedQuery,
                results.isEmpty() ? KnowledgeSearchResponse.NO_EVIDENCE_MESSAGE : null,
                results);
    }
}
