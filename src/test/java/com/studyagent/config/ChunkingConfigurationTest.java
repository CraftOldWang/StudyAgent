package com.studyagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyagent.algo.chunk.StructuredChunker;
import com.studyagent.algo.chunk.TokenCounter;
import com.studyagent.algo.chunk.TokenWindowChunker;
import org.junit.jupiter.api.Test;

class ChunkingConfigurationTest {

    @Test
    void createsChunkersWithOneTokenCounter() {
        ChunkingConfiguration configuration = new ChunkingConfiguration();
        TokenCounter tokenCounter = configuration.tokenCounter();

        assertThat(configuration.structuredChunker(tokenCounter)).isInstanceOf(StructuredChunker.class);
        assertThat(configuration.tokenWindowChunker(tokenCounter)).isInstanceOf(TokenWindowChunker.class);
    }
}
