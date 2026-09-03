package com.studyagent.algo.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextChunkerTest {

    @Test
    void compatibilityEntryShouldDelegateToStructuredTokenChunking() {
        String text = "# 标题\n\n" + "内容 ".repeat(30);
        StructuredChunker delegate = new StructuredChunker(new JtokkitTokenCounter());

        assertThat(TextChunker.chunk(text, 10, 2))
                .containsExactlyElementsOf(delegate.chunk(text, 10, 2).stream()
                        .map(ChunkSegment::content)
                        .toList());
    }

    @Test
    void compatibilityEntryShouldReturnNoChunksForMissingText() {
        assertThat(TextChunker.chunk(null, 900, 120)).isEmpty();
        assertThat(TextChunker.parentChunks(" \n\t", 2400, 240)).isEmpty();
    }
}
