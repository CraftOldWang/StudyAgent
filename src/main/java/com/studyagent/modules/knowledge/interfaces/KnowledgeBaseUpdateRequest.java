package com.studyagent.modules.knowledge.interfaces;

/**
 * 更新知识库请求，字段为空表示不修改。
 */
public record KnowledgeBaseUpdateRequest(
        String name,
        String description,
        String status
) {
}
