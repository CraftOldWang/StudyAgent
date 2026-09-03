package com.studyagent.algo.metric;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recall@K 指标计算器。
 *
 * <p>Recall@K 的含义是：在前 K 个检索结果里，覆盖了多少人工标注的正确 chunk。
 * 例如 expected=[1,2]，top3=[9,1,8]，则 Recall@3 = 1 / 2 = 0.5。
 * 这个指标适合先衡量检索链路“有没有把证据召回来”，比回答质量评测更稳定，也不依赖 LLM 评分。</p>
 */
public final class RecallMetricCalculator {

    private RecallMetricCalculator() {
    }

    /**
     * 计算单条 case 在指定 K 下的 Recall。
     *
     * <p>检索结果可能因为父子上下文扩展出现重复 chunkId，因此这里先保留顺序去重。
     * expectedChunkIds 也去重，避免人工标注里误重复导致分母被放大。</p>
     */
    public static <T> double recallAtK(List<T> expectedChunkIds, List<T> retrievedChunkIds, int k) {
        if (expectedChunkIds == null || expectedChunkIds.isEmpty() || k <= 0) {
            return 0.0d;
        }
        Set<T> expected = new LinkedHashSet<>(expectedChunkIds);
        List<T> topK = orderedDistinct(retrievedChunkIds).stream()
                .limit(k)
                .toList();
        long hits = topK.stream()
                .filter(expected::contains)
                .count();
        return hits / (double) expected.size();
    }

    /**
     * 保留检索顺序的去重。
     */
    public static <T> List<T> orderedDistinct(List<T> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return new LinkedHashSet<>(chunkIds).stream().toList();
    }
}
