package com.studyagent.ingest.pipeline;

public enum PipelineStatus {
    STORED,
    PARSING,
    TRANSCRIBING,
    TRANSCRIBED,
    PARSED,
    CHUNKING,
    CHUNKED,
    EMBEDDING,
    EMBEDDED,
    INDEXING,
    INDEXED,
    FAILED
}
