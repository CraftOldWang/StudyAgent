package com.studyagent.infrastructure.embedding;

public interface EmbeddingService {
    float[] embed(String text);

    default float[] embedQuery(String text) {
        return embed(text);
    }
}
