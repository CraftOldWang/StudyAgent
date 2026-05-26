package com.studyagent.modules.rag.domain;

import java.util.List;

public record RagSearchResult(
        String question,
        List<RagReference> references
) {
}
