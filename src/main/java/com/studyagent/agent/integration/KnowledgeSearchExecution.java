package com.studyagent.agent.integration;

import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import java.util.LinkedHashSet;
import java.util.Set;

public record KnowledgeSearchExecution(
        KnowledgeSearchResponse response,
        Set<String> retrievedChunkIds
) {

    public KnowledgeSearchExecution(KnowledgeSearchResponse response) {
        this(response, chunkIds(response));
    }

    public KnowledgeSearchExecution append(KnowledgeSearchResponse latest) {
        LinkedHashSet<String> combined = new LinkedHashSet<>(retrievedChunkIds);
        combined.addAll(chunkIds(latest));
        return new KnowledgeSearchExecution(latest, Set.copyOf(combined));
    }

    private static Set<String> chunkIds(KnowledgeSearchResponse response) {
        if (response == null || response.hits() == null) {
            return Set.of();
        }
        return response.hits().stream()
                .map(KnowledgeSearchResponse.Result::chunkId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
