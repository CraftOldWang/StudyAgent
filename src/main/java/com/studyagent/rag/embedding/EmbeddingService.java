package com.studyagent.rag.embedding;

public interface EmbeddingService {

    float[] embed(String text, EmbeddingPurpose purpose);
}
