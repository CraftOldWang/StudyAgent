package com.studyagent.modules.rag.domain;

import java.util.List;

public record RagAnswer(
        String answer,
        List<RagReference> references
) {
}
