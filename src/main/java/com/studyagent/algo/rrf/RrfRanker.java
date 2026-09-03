package com.studyagent.algo.rrf;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion 排序器，用于融合 BM25、向量等多路召回结果。
 */
public class RrfRanker {

    private final int rankConstant;

    /**
     * 创建 RRF 排序器，rankConstant 越大，靠前排名优势越平滑。
     */
    public RrfRanker(int rankConstant) {
        this.rankConstant = Math.max(rankConstant, 1);
    }

    /**
     * 对多路已经按相关性排序的候选列表进行融合排序。
     */
    public List<RrfRankedItem> rank(Collection<List<RrfCandidate>> rankedLists) {
        Map<Long, MutableRankedItem> itemMap = new LinkedHashMap<>();
        for (List<RrfCandidate> rankedList : rankedLists) {
            for (int index = 0; index < rankedList.size(); index++) {
                RrfCandidate candidate = rankedList.get(index);
                MutableRankedItem item = itemMap.computeIfAbsent(
                        candidate.chunkId(),
                        chunkId -> new MutableRankedItem(candidate)
                );
                // RRF 只关心每路召回中的排名位置，不直接比较不同检索器的原始分数。
                item.score += 1.0d / (rankConstant + index + 1.0d);
                item.sourceScores.merge(candidate.source(), candidate.originalScore(), Math::max);
            }
        }
        return itemMap.values().stream()
                // Stream 排序是稳定的；总分并列时保留首次召回顺序，避免用 chunkId 引入无关偏好。
                .sorted(Comparator.comparingDouble(MutableRankedItem::score).reversed())
                .map(MutableRankedItem::toRankedItem)
                .toList();
    }

    /**
     * 单路召回候选项。
     */
    public record RrfCandidate(
            Long chunkId,
            String source,
            double originalScore
    ) {
    }

    /**
     * 融合后的排序结果，保留每个来源的原始分数便于调试。
     */
    public record RrfRankedItem(
            Long chunkId,
            double score,
            Map<String, Double> sourceScores
    ) {
    }

    private static final class MutableRankedItem {
        private final RrfCandidate candidate;
        private final Map<String, Double> sourceScores = new HashMap<>();
        private double score;

        private MutableRankedItem(RrfCandidate candidate) {
            this.candidate = candidate;
        }

        private double score() {
            return score;
        }

        private RrfRankedItem toRankedItem() {
            return new RrfRankedItem(candidate.chunkId(), score, new LinkedHashMap<>(sourceScores));
        }
    }
}
