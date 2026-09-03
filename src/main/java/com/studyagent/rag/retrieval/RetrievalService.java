package com.studyagent.rag.retrieval;

import com.studyagent.algo.rrf.RrfRanker;
import com.studyagent.common.exception.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 编排 BM25、向量、RRF 和父块上下文四种检索模式。
 */
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final int RRF_RANK_CONSTANT = 60;

    private final BM25Retriever bm25Retriever;
    private final VectorRetriever vectorRetriever;
    private final ParentAggregator parentAggregator;

    public List<RetrievalHit> retrieve(
            RetrievalMode mode,
            String userId,
            String knowledgeBaseId,
            String query,
            float[] queryVector,
            int candidateLimit,
            int topK
    ) {
        validateRequest(mode, userId, knowledgeBaseId, candidateLimit, topK);
        List<RetrievalHit> hits = switch (mode) {
            case BM25 -> bm25Retriever.retrieve(userId, knowledgeBaseId, query, candidateLimit);
            case VECTOR -> vectorRetriever.retrieve(userId, knowledgeBaseId, queryVector, candidateLimit);
            case RRF -> fusedHits(userId, knowledgeBaseId, query, queryVector, candidateLimit);
            case PARENT -> parentAggregator.aggregate(
                    userId,
                    knowledgeBaseId,
                    fusedHits(userId, knowledgeBaseId, query, queryVector, candidateLimit)
            );
        };
        return hits.stream().limit(topK).toList();
    }

    private List<RetrievalHit> fusedHits(
            String userId,
            String knowledgeBaseId,
            String query,
            float[] queryVector,
            int candidateLimit
    ) {
        List<RetrievalHit> bm25Hits =
                bm25Retriever.retrieve(userId, knowledgeBaseId, query, candidateLimit);
        List<RetrievalHit> vectorHits =
                vectorRetriever.retrieve(userId, knowledgeBaseId, queryVector, candidateLimit);

        Map<String, Long> surrogateIdByChunkId = new LinkedHashMap<>();
        Map<Long, RetrievalHit> hitBySurrogateId = new LinkedHashMap<>();
        registerHits(bm25Hits, surrogateIdByChunkId, hitBySurrogateId);
        registerHits(vectorHits, surrogateIdByChunkId, hitBySurrogateId);

        RrfRanker ranker = new RrfRanker(RRF_RANK_CONSTANT);
        return ranker.rank(List.of(
                        candidates(bm25Hits, surrogateIdByChunkId),
                        candidates(vectorHits, surrogateIdByChunkId)
                )).stream()
                .map(ranked -> fusedHit(hitBySurrogateId.get(ranked.chunkId()), ranked.score()))
                .toList();
    }

    private void registerHits(
            List<RetrievalHit> hits,
            Map<String, Long> surrogateIdByChunkId,
            Map<Long, RetrievalHit> hitBySurrogateId
    ) {
        for (RetrievalHit hit : hits) {
            Long surrogateId = surrogateIdByChunkId.computeIfAbsent(
                    hit.chunkId(),
                    ignored -> (long) surrogateIdByChunkId.size() + 1L
            );
            hitBySurrogateId.putIfAbsent(surrogateId, hit);
        }
    }

    private List<RrfRanker.RrfCandidate> candidates(
            List<RetrievalHit> hits,
            Map<String, Long> surrogateIdByChunkId
    ) {
        return hits.stream()
                .map(hit -> new RrfRanker.RrfCandidate(
                        surrogateIdByChunkId.get(hit.chunkId()),
                        hit.strategy().name(),
                        hit.score()
                ))
                .toList();
    }

    private RetrievalHit fusedHit(RetrievalHit source, double score) {
        return new RetrievalHit(
                source.chunkId(),
                source.parentChunkId(),
                source.content(),
                score,
                RetrievalStrategy.RRF
        );
    }

    private void validateRequest(
            RetrievalMode mode,
            String userId,
            String knowledgeBaseId,
            int candidateLimit,
            int topK
    ) {
        if (mode == null) {
            throw new BusinessException("检索模式不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("用户范围不能为空");
        }
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw new BusinessException("知识库范围不能为空");
        }
        if (candidateLimit <= 0) {
            throw new BusinessException("候选数量必须大于 0");
        }
        if (topK <= 0) {
            throw new BusinessException("topK 必须大于 0");
        }
    }
}
