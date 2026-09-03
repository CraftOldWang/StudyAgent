package com.studyagent.algo.rrf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
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
        assertThat(rankedItems.getFirst().score())
                .isCloseTo(1.0d / 62.0d + 1.0d / 61.0d, within(1.0e-12));
        assertThat(rankedItems.getFirst().sourceScores())
                .containsEntry("bm25", 8.0)
                .containsEntry("vector", 0.9);
    }

    @Test
    void rankShouldReturnEmptyResultForEmptyRoutes() {
        RrfRanker ranker = new RrfRanker(60);

        assertThat(ranker.rank(List.of())).isEmpty();
        assertThat(ranker.rank(List.of(List.of(), List.of()))).isEmpty();
    }

    @Test
    void rankShouldPreserveSingleRouteOrderAndScores() {
        RrfRanker ranker = new RrfRanker(60);

        List<RrfRanker.RrfRankedItem> rankedItems = ranker.rank(List.of(List.of(
                new RrfRanker.RrfCandidate(10L, "bm25", 12.0),
                new RrfRanker.RrfCandidate(20L, "bm25", 8.0)
        )));

        assertThat(rankedItems).extracting(RrfRanker.RrfRankedItem::chunkId)
                .containsExactly(10L, 20L);
        assertThat(rankedItems.getFirst().score())
                .isCloseTo(1.0d / 61.0d, within(1.0e-12));
        assertThat(rankedItems.getFirst().sourceScores())
                .containsExactlyEntriesOf(Map.of("bm25", 12.0));
    }

    @Test
    void rankShouldKeepFirstEncounterOrderWhenFusedScoresTie() {
        RrfRanker ranker = new RrfRanker(60);

        List<RrfRanker.RrfRankedItem> rankedItems = ranker.rank(List.of(
                List.of(
                        new RrfRanker.RrfCandidate(20L, "bm25", 12.0),
                        new RrfRanker.RrfCandidate(10L, "bm25", 8.0)
                ),
                List.of(
                        new RrfRanker.RrfCandidate(10L, "vector", 0.9),
                        new RrfRanker.RrfCandidate(20L, "vector", 0.7)
                )
        ));

        assertThat(rankedItems).extracting(RrfRanker.RrfRankedItem::chunkId)
                .containsExactly(20L, 10L);
        assertThat(rankedItems.get(0).score())
                .isCloseTo(rankedItems.get(1).score(), within(1.0e-12));
    }
}
