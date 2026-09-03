package com.studyagent.algo.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JtokkitTokenCounterTest {

    private final TokenCounter tokenCounter = new JtokkitTokenCounter();

    @Test
    void shouldUseDeterministicCl100kBaseCounting() {
        assertThat(tokenCounter.count("hello world")).isEqualTo(2);
        assertThat(tokenCounter.count("hello world")).isEqualTo(tokenCounter.count("hello world"));
    }

    @Test
    void shouldTreatSpecialTokenTextAsOrdinaryContent() {
        assertThat(tokenCounter.count("prefix <|endoftext|> suffix")).isPositive();
    }
}
