package com.studyagent.config;

import com.studyagent.algo.chunk.JtokkitTokenCounter;
import com.studyagent.algo.chunk.StructuredChunker;
import com.studyagent.algo.chunk.TokenCounter;
import com.studyagent.algo.chunk.TokenWindowChunker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChunkingConfiguration {

    @Bean
    TokenCounter tokenCounter() {
        return new JtokkitTokenCounter();
    }

    @Bean
    StructuredChunker structuredChunker(TokenCounter tokenCounter) {
        return new StructuredChunker(tokenCounter);
    }

    @Bean
    TokenWindowChunker tokenWindowChunker(TokenCounter tokenCounter) {
        return new TokenWindowChunker(tokenCounter);
    }
}
