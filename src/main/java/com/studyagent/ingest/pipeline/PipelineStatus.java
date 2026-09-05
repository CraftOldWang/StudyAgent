package com.studyagent.ingest.pipeline;

public enum PipelineStatus {
    STORED,
    PARSING,
    PARSED,
    CHUNKING,
    CHUNKED,
    EMBEDDING,
    EMBEDDED,
    INDEXING,
    INDEXED,
    FAILED
}
