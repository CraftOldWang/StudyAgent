package com.studyagent.modules.rag.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RrfRanker {

    private final int rankConstant;

    public RrfRanker(int rankConstant) {
        this.rankConstant = Math.max(rankConstant, 1);
    }

    public List<RrfRankedItem> rank(Collection<List<RrfCandidate>> rankedLists) {
        Map<Long, MutableRankedItem> itemMap = new LinkedHashMap<>();
        for (List<RrfCandidate> rankedList : rankedLists) {
            for (int index = 0; index < rankedList.size(); index++) {
                RrfCandidate candidate = rankedList.get(index);
                MutableRankedItem item = itemMap.computeIfAbsent(
                        candidate.chunkId(),
                        chunkId -> new MutableRankedItem(candidate)
                );
                item.score += 1.0d / (rankConstant + index + 1.0d);
                item.sourceScores.merge(candidate.source(), candidate.originalScore(), Math::max);
            }
        }
        return itemMap.values().stream()
                .sorted(Comparator.comparingDouble(MutableRankedItem::score).reversed()
                        .thenComparing(item -> item.candidate.chunkId()))
                .map(MutableRankedItem::toRankedItem)
                .toList();
    }

    public record RrfCandidate(
            Long chunkId,
            String source,
            double originalScore
    ) {
    }

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
