package com.studyagent.algo.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RecallMetricCalculatorTest {

    @Test
    void recallAtKShouldMeasureExpectedChunkCoverage() {
        double recallAt2 = RecallMetricCalculator.recallAtK(
                List.of(10L, 20L),
                List.of(99L, 10L, 20L),
                2
        );

        assertThat(recallAt2).isEqualTo(0.5d);
    }

    @Test
    void recallAtKShouldDeduplicateRetrievedChunksBeforeCalculating() {
        double recallAt3 = RecallMetricCalculator.recallAtK(
                List.of(10L, 20L),
                List.of(10L, 10L, 20L),
                2
        );

        assertThat(recallAt3).isEqualTo(1.0d);
    }
}
