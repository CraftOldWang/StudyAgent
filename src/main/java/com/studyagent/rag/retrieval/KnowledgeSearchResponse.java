package com.studyagent.rag.retrieval;

import java.util.List;

public record KnowledgeSearchResponse(
        String query,
        String message,
        List<Result> hits
) {

    public static final String NO_EVIDENCE_MESSAGE = "当前知识库没有可支持该问题的资料依据。";

    public record Result(
            String chunkId,
            String content,
            RetrievalHit.Provenance provenance,
            double score
    ) {
    }
}
