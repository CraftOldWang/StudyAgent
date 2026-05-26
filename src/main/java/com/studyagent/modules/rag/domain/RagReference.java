package com.studyagent.modules.rag.domain;

public record RagReference(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        Integer chunkIndex,
        String content,
        double score
) {
}
