package com.studyagent.agent.integration;

import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KnowledgeSearchExecution {

    private final List<KnowledgeSearchResponse> responses = new ArrayList<>();

    public KnowledgeSearchExecution() {
    }

    public KnowledgeSearchExecution(KnowledgeSearchResponse response) {
        append(response);
    }

    public KnowledgeSearchExecution append(KnowledgeSearchResponse response) {
        responses.add(response);
        return this;
    }

    public KnowledgeSearchResponse response() {
        return responses.isEmpty() ? null : responses.getLast();
    }

    public boolean invoked() {
        return !responses.isEmpty();
    }

    public List<KnowledgeSearchResponse.Result> hits() {
        Map<String, KnowledgeSearchResponse.Result> distinctHits = new LinkedHashMap<>();
        responses.stream()
                .flatMap(response -> response.hits().stream())
                .forEach(hit -> distinctHits.putIfAbsent(hit.chunkId(), hit));
        return List.copyOf(distinctHits.values());
    }

    public Set<String> retrievedChunkIds() {
        LinkedHashSet<String> chunkIds = new LinkedHashSet<>();
        hits().stream()
                .map(KnowledgeSearchResponse.Result::chunkId)
                .forEach(chunkIds::add);
        return Set.copyOf(chunkIds);
    }
}
