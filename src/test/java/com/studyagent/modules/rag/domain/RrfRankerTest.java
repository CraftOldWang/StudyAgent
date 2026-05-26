package com.studyagent.modules.rag.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RrfRankerTest {

    @Test
    void rankShouldFuseBm25AndVectorResults() {
        RrfRanker ranker = new RrfRanker(60);

        List<RrfRanker.RrfRankedItem> rankedItems = ranker.rank(List.of(
                List.of(
                        new RrfRanker.RrfCandidate(10L, "bm25", 12.0),
                        new RrfRanker.RrfCandidate(20L, "bm25", 8.0)
                ),
                List.of(
                        new RrfRanker.RrfCandidate(20L, "vector", 0.9),
                        new RrfRanker.RrfCandidate(30L, "vector", 0.7)
                )
        ));

        assertThat(rankedItems).extracting(RrfRanker.RrfRankedItem::chunkId)
                .containsExactly(20L, 10L, 30L);
        assertThat(rankedItems.getFirst().sourceScores())
                .containsEntry("bm25", 8.0)
                .containsEntry("vector", 0.9);
    }
}
