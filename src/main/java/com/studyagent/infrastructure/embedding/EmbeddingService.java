package com.studyagent.infrastructure.embedding;

public interface EmbeddingService {
    float[] embed(String text);
}
