package com.studyagent.modules.evaluation.application;

import com.studyagent.config.RagProperties;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
import com.studyagent.infrastructure.search.SearchHitChunk;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.modules.evaluation.domain.RagRetrievalStrategy;
import com.studyagent.algo.metric.RecallMetricCalculator;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.algo.rrf.RrfRanker;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * RAG Recall 离线评测服务。
 *
 * <p>该服务不生成答案，只比较“检索结果是否包含标注证据 chunk”。这样可以把检索质量和 LLM 回答质量拆开看，
 * 也更适合对比父子检索、RRF、纯向量、纯关键词这些策略的真实差异。</p>
 */
@Service
@RequiredArgsConstructor
public class RagRecallEvaluationService {

    private static final List<Integer> DEFAULT_TOP_K_VALUES = List.of(1, 3, 5, 10);

    private final EmbeddingService embeddingService;
    private final ElasticsearchChunkIndexer elasticsearchChunkIndexer;
    private final RagProperties ragProperties;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentMapper documentMapper;

    /**
     * 执行 Recall 评测。
     */
    public RagRecallEvaluationReport evaluate(RagRecallEvaluationRequest request) {
        Long userId = request.userId() == null ? KnowledgeBaseService.DEFAULT_USER_ID : request.userId();
        validateRequest(request);
        validateExpectedChunks(userId, request.knowledgeBaseIds(), request.cases());
        List<Integer> topKValues = normalizeTopKValues(request.topKValues());
        int maxTopK = topKValues.stream().mapToInt(Integer::intValue).max().orElse(1);
        List<RagRetrievalStrategy> strategies = normalizeStrategies(request.strategies());
        boolean includeCaseDetails = request.includeCaseDetails() == null || request.includeCaseDetails();

        List<RagRecallEvaluationReport.StrategyReport> strategyReports = new ArrayList<>();
        for (RagRetrievalStrategy strategy : strategies) {
            strategyReports.add(evaluateStrategy(
                    userId,
                    request.knowledgeBaseIds(),
                    request.cases(),
                    topKValues,
                    maxTopK,
                    strategy,
                    includeCaseDetails
            ));
        }
        return new RagRecallEvaluationReport(
                userId,
                request.knowledgeBaseIds(),
                request.cases().size(),
                topKValues,
                strategyReports
        );
    }

    /**
     * 针对单个策略遍历所有 case，并做 macro-average Recall。
     */
    private RagRecallEvaluationReport.StrategyReport evaluateStrategy(
            Long userId,
            List<Long> knowledgeBaseIds,
            List<RagEvalCase> cases,
            List<Integer> topKValues,
            int maxTopK,
            RagRetrievalStrategy strategy,
            boolean includeCaseDetails
    ) {
        Map<Integer, Double> seedRecallSums = new LinkedHashMap<>();
        Map<Integer, Double> contextRecallSums = new LinkedHashMap<>();
        for (Integer topK : topKValues) {
            seedRecallSums.put(topK, 0.0d);
            contextRecallSums.put(topK, 0.0d);
        }
        List<RagRecallEvaluationReport.CaseResult> caseResults = new ArrayList<>();
        long totalLatencyMillis = 0L;

        for (RagEvalCase evalCase : cases) {
            List<String> expectedSeedChunkIds = RecallMetricCalculator.orderedDistinct(evalCase.expectedChunkIds());
            List<String> expectedContextChunkIds = resolveExpectedContextChunkIds(
                    userId,
                    knowledgeBaseIds,
                    expectedSeedChunkIds,
                    strategy
            );
            long startedAt = System.nanoTime();
            RetrievalResult retrievalResult = retrieve(userId, knowledgeBaseIds, evalCase.question(), strategy, maxTopK);
            long latencyMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            totalLatencyMillis += latencyMillis;

            Map<Integer, Double> seedCaseRecall = new LinkedHashMap<>();
            Map<Integer, Double> contextCaseRecall = new LinkedHashMap<>();
            for (Integer topK : topKValues) {
                double seedRecall = RecallMetricCalculator.recallAtK(
                        expectedSeedChunkIds,
                        retrievalResult.seedChunkIds(),
                        topK
                );
                double contextRecall = RecallMetricCalculator.recallAtK(
                        expectedContextChunkIds,
                        retrievalResult.contextChunkIds(),
                        topK
                );
                seedCaseRecall.put(topK, seedRecall);
                contextCaseRecall.put(topK, contextRecall);
                seedRecallSums.compute(topK, (ignored, sum) -> sum + seedRecall);
                contextRecallSums.compute(topK, (ignored, sum) -> sum + contextRecall);
            }
            if (includeCaseDetails) {
                caseResults.add(new RagRecallEvaluationReport.CaseResult(
                        evalCase.question(),
                        expectedSeedChunkIds,
                        expectedContextChunkIds,
                        retrievalResult.seedChunkIds(),
                        retrievalResult.contextChunkIds(),
                        seedCaseRecall,
                        contextCaseRecall,
                        latencyMillis
                ));
            }
        }

        Map<Integer, Double> averageSeedRecall = average(seedRecallSums, cases.size());
        Map<Integer, Double> averageContextRecall = average(contextRecallSums, cases.size());
        return new RagRecallEvaluationReport.StrategyReport(
                strategy,
                averageSeedRecall,
                averageContextRecall,
                round(totalLatencyMillis / (double) cases.size()),
                totalLatencyMillis,
                includeCaseDetails ? caseResults : List.of()
        );
    }

    /**
     * 根据策略执行真实检索。所有策略都使用当前 ES 索引和当前 embedding 配置，保证评测贴近线上链路。
     */
    private RetrievalResult retrieve(
            Long userId,
            List<Long> knowledgeBaseIds,
            String question,
            RagRetrievalStrategy strategy,
            int maxTopK
    ) {
        return switch (strategy) {
            case BM25_ONLY -> childOnlyResultFromHits(elasticsearchChunkIndexer.bm25Search(
                            userId,
                            knowledgeBaseIds,
                            question,
                            Math.max(maxTopK, ragProperties.bm25CandidateSize())
                    ));
            case VECTOR_ONLY -> childOnlyResultFromHits(vectorSearch(
                    userId,
                    knowledgeBaseIds,
                    question,
                    Math.max(maxTopK, ragProperties.vectorCandidateSize())
            ));
            case HYBRID_RRF -> childOnlyResultFromIds(hybridRrf(userId, knowledgeBaseIds, question).stream()
                    .limit(maxTopK)
                    .toList());
            case HYBRID_RRF_PARENT -> parentChildResult(userId, knowledgeBaseIds, hybridRrf(userId, knowledgeBaseIds, question).stream()
                    .limit(maxTopK)
                    .toList());
        };
    }

    private RetrievalResult childOnlyResultFromHits(List<SearchHitChunk> hits) {
        return childOnlyResultFromIds(hits.stream().map(SearchHitChunk::chunkId).toList());
    }

    private RetrievalResult childOnlyResultFromIds(List<String> seedChunkIds) {
        List<String> distinctSeedChunkIds = RecallMetricCalculator.orderedDistinct(seedChunkIds);
        return new RetrievalResult(distinctSeedChunkIds, distinctSeedChunkIds);
    }

    private List<SearchHitChunk> vectorSearch(Long userId, List<Long> knowledgeBaseIds, String question, int topK) {
        float[] queryVector = embeddingService.embedQuery(question);
        return elasticsearchChunkIndexer.vectorSearch(userId, knowledgeBaseIds, queryVector, topK);
    }

    /**
     * 双路召回后用 RRF 融合。RRF 只比较各路排名，不直接比较 BM25 分数和向量分数。
     */
    private List<String> hybridRrf(Long userId, List<Long> knowledgeBaseIds, String question) {
        List<SearchHitChunk> bm25Hits = elasticsearchChunkIndexer.bm25Search(
                userId,
                knowledgeBaseIds,
                question,
                ragProperties.bm25CandidateSize()
        );
        List<SearchHitChunk> vectorHits = vectorSearch(
                userId,
                knowledgeBaseIds,
                question,
                ragProperties.vectorCandidateSize()
        );
        Map<String, Long> surrogateIdByChunkId = new LinkedHashMap<>();
        Map<Long, SearchHitChunk> hitBySurrogateId = new LinkedHashMap<>();
        registerHits(bm25Hits, surrogateIdByChunkId, hitBySurrogateId);
        registerHits(vectorHits, surrogateIdByChunkId, hitBySurrogateId);
        return new RrfRanker(ragProperties.rrfK()).rank(List.of(
                        candidates("bm25", bm25Hits, surrogateIdByChunkId),
                        candidates("vector", vectorHits, surrogateIdByChunkId)
                )).stream()
                .map(item -> chunkIdBySurrogateId(surrogateIdByChunkId, item.chunkId()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<RrfRanker.RrfCandidate> candidates(
            String source,
            List<SearchHitChunk> hits,
            Map<String, Long> surrogateIdByChunkId
    ) {
        return hits.stream()
                .map(hit -> new RrfRanker.RrfCandidate(surrogateIdByChunkId.get(hit.chunkId()), source, hit.score()))
                .toList();
    }

    private void registerHits(
            List<SearchHitChunk> hits,
            Map<String, Long> surrogateIdByChunkId,
            Map<Long, SearchHitChunk> hitBySurrogateId
    ) {
        for (SearchHitChunk hit : hits) {
            Long surrogateId = surrogateIdByChunkId.computeIfAbsent(
                    hit.chunkId(),
                    ignored -> (long) surrogateIdByChunkId.size() + 1L
            );
            hitBySurrogateId.putIfAbsent(surrogateId, hit);
        }
    }

    private String chunkIdBySurrogateId(Map<String, Long> surrogateIdByChunkId, Long surrogateId) {
        for (Map.Entry<String, Long> entry : surrogateIdByChunkId.entrySet()) {
            if (entry.getValue().equals(surrogateId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 对 RRF 种子子 chunk 做父子检索上下文补全。
     *
     * <p>注意：这里的 Recall@K 是对最终返回的 chunk 顺序计算的。
     * 因此 HYBRID_RRF_PARENT 可以回答“父块补全后，正确证据是否进入最终上下文”。</p>
     */
    private RetrievalResult parentChildResult(Long userId, List<Long> knowledgeBaseIds, List<String> seedChunkIds) {
        List<String> distinctSeedChunkIds = RecallMetricCalculator.orderedDistinct(seedChunkIds);
        List<String> contextChunkIds = parentChildContext(userId, knowledgeBaseIds, distinctSeedChunkIds);
        return new RetrievalResult(distinctSeedChunkIds, contextChunkIds);
    }

    private List<String> parentChildContext(Long userId, List<Long> knowledgeBaseIds, List<String> seedChunkIds) {
        if (seedChunkIds.isEmpty()) {
            return List.of();
        }
        Map<String, SearchHitChunk> seedHitMap = new LinkedHashMap<>();
        for (SearchHitChunk hit : elasticsearchChunkIndexer.searchByChunkIds(userId, seedChunkIds)) {
            seedHitMap.put(hit.chunkId(), hit);
        }
        Map<String, String> parentIds = new LinkedHashMap<>();
        List<String> fallbackSeedChunkIds = new ArrayList<>();
        for (String seedChunkId : seedChunkIds) {
            SearchHitChunk seed = seedHitMap.get(seedChunkId);
            if (seed == null) {
                continue;
            }
            if (seed.parentChunkId() == null) {
                fallbackSeedChunkIds.add(seed.chunkId());
                continue;
            }
            parentIds.putIfAbsent(seed.parentChunkId(), seed.parentChunkId());
        }
        Map<String, SearchHitChunk> parentMap = new LinkedHashMap<>();
        Map<String, String> contextChunkIds = new LinkedHashMap<>();
        if (!parentIds.isEmpty()) {
            for (SearchHitChunk parentChunk : elasticsearchChunkIndexer.searchParentChunks(
                    userId,
                    knowledgeBaseIds,
                    parentIds.keySet().stream().toList()
            )) {
                parentMap.put(parentChunk.chunkId(), parentChunk);
            }
            // ES terms 查询不承诺返回顺序；这里按 RRF 种子映射出的父块顺序重排，保证 Recall@K 可解释。
            for (String parentId : parentIds.keySet()) {
                SearchHitChunk parentChunk = parentMap.get(parentId);
                if (parentChunk != null) {
                    contextChunkIds.putIfAbsent(parentChunk.chunkId(), parentChunk.chunkId());
                }
            }
        }
        for (String fallbackSeedChunkId : fallbackSeedChunkIds) {
            contextChunkIds.putIfAbsent(fallbackSeedChunkId, fallbackSeedChunkId);
        }
        return new ArrayList<>(contextChunkIds.keySet());
    }

    /**
     * 将人工/DeepSeek 标注的子 chunk 真值映射到父 chunk 真值。
     *
     * <p>父子检索最终交给模型的是父块上下文。如果不把 CHILD expectedChunkIds 映射到 parentChunkId，
     * HYBRID_RRF_PARENT 的上下文召回会被错误评估。因此报告中同时返回 seedRecall 与 contextRecall，
     * 前者回答“命中点是否召回”，后者回答“最终上下文是否覆盖”。这里从 ES 读取子文档元数据，
     * 保持评测链路和线上父子检索一致，不再回 MySQL 取父块正文。</p>
     */
    private List<String> resolveExpectedContextChunkIds(
            Long userId,
            List<Long> knowledgeBaseIds,
            List<String> expectedSeedChunkIds,
            RagRetrievalStrategy strategy
    ) {
        if (expectedSeedChunkIds.isEmpty()) {
            return List.of();
        }
        if (strategy != RagRetrievalStrategy.HYBRID_RRF_PARENT) {
            // 非父子策略最终上下文仍然是子 chunk，因此上下文 Recall 与种子 Recall 使用同一组真值。
            return expectedSeedChunkIds;
        }
        Map<String, String> contextIds = new LinkedHashMap<>();
        for (SearchHitChunk chunk : elasticsearchChunkIndexer.searchByChunkIds(userId, expectedSeedChunkIds)) {
            if (knowledgeBaseIds.contains(chunk.knowledgeBaseId()) && chunk.parentChunkId() != null) {
                contextIds.putIfAbsent(chunk.parentChunkId(), chunk.parentChunkId());
                continue;
            }
            contextIds.putIfAbsent(chunk.chunkId(), chunk.chunkId());
        }
        if (contextIds.isEmpty()) {
            return expectedSeedChunkIds;
        }
        return new ArrayList<>(contextIds.keySet());
    }

    private void validateRequest(RagRecallEvaluationRequest request) {
        if (request.knowledgeBaseIds() == null || request.knowledgeBaseIds().isEmpty()) {
            throw new BusinessException("知识库范围不能为空");
        }
        if (request.cases() == null || request.cases().isEmpty()) {
            throw new BusinessException("评测样本不能为空");
        }
        for (RagEvalCase evalCase : request.cases()) {
            if (evalCase.question() == null || evalCase.question().isBlank()) {
                throw new BusinessException("评测问题不能为空");
            }
            if (evalCase.expectedChunkIds() == null || evalCase.expectedChunkIds().isEmpty()) {
                throw new BusinessException("expectedChunkIds 不能为空");
            }
            if (evalCase.expectedChunkIds().stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new BusinessException("expectedChunkIds 只能包含非空字符串");
            }
        }
    }

    /**
     * 在开始检索前校验所有真值 chunk 的 V2 归属，避免缺失或越权数据被静默计为低召回。
     */
    private void validateExpectedChunks(
            Long userId,
            List<Long> knowledgeBaseIds,
            List<RagEvalCase> cases
    ) {
        Set<String> expectedChunkIds = new LinkedHashSet<>();
        for (RagEvalCase evalCase : cases) {
            expectedChunkIds.addAll(evalCase.expectedChunkIds());
        }
        List<DocumentChunk> chunks = documentChunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .in(DocumentChunk::getChunkId, expectedChunkIds)
        );
        Map<String, DocumentChunk> chunksByChunkId = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks) {
            chunksByChunkId.put(chunk.getChunkId(), chunk);
        }
        List<String> missingChunkIds = expectedChunkIds.stream()
                .filter(chunkId -> !chunksByChunkId.containsKey(chunkId))
                .toList();
        if (!missingChunkIds.isEmpty()) {
            throw new BusinessException("评测真值 chunk 不存在: " + missingChunkIds);
        }

        Set<Long> documentIds = new LinkedHashSet<>();
        for (DocumentChunk chunk : chunksByChunkId.values()) {
            if (chunk.getDocumentId() == null) {
                throw new BusinessException("评测真值 chunk 缺少 document 归属: " + chunk.getChunkId());
            }
            documentIds.add(chunk.getDocumentId());
        }
        List<Document> documents = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().in(Document::getId, documentIds)
        );
        Map<Long, Document> documentsById = new LinkedHashMap<>();
        for (Document document : documents) {
            documentsById.put(document.getId(), document);
        }
        for (DocumentChunk chunk : chunksByChunkId.values()) {
            Document document = documentsById.get(chunk.getDocumentId());
            if (document == null) {
                throw new BusinessException(
                        "评测真值 chunk 关联 document 不存在: " + chunk.getChunkId()
                );
            }
            if (!userId.equals(document.getUserId())
                    || !knowledgeBaseIds.contains(document.getKnowledgeBaseId())) {
                throw new BusinessException(
                        "评测真值 chunk 超出 user/knowledgeBase scope: " + chunk.getChunkId()
                );
            }
        }
    }

    private List<Integer> normalizeTopKValues(List<Integer> topKValues) {
        List<Integer> values = topKValues == null || topKValues.isEmpty() ? DEFAULT_TOP_K_VALUES : topKValues;
        List<Integer> normalized = values.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .sorted()
                .toList();
        if (normalized.isEmpty()) {
            throw new BusinessException("topKValues 必须至少包含一个正整数");
        }
        return normalized;
    }

    private List<RagRetrievalStrategy> normalizeStrategies(List<RagRetrievalStrategy> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            return EnumSet.allOf(RagRetrievalStrategy.class).stream().toList();
        }
        return strategies.stream()
                .distinct()
                .toList();
    }

    private double round(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private Map<Integer, Double> average(Map<Integer, Double> sums, int caseCount) {
        Map<Integer, Double> average = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> entry : sums.entrySet()) {
            average.put(entry.getKey(), round(entry.getValue() / caseCount));
        }
        return average;
    }

    private record RetrievalResult(
            List<String> seedChunkIds,
            List<String> contextChunkIds
    ) {
    }
}
