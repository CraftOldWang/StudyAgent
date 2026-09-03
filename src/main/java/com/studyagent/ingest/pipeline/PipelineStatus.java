package com.studyagent.ingest.pipeline;

public enum PipelineStatus {
    PENDING,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    COMPLETED,
    FAILED
}
