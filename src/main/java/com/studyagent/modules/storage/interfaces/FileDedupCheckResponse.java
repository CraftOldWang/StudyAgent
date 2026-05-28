package com.studyagent.modules.storage.interfaces;

/**
 * 文件去重检查响应，描述当前哈希是否已经存在可复用文件。
 */
public record FileDedupCheckResponse(
        boolean duplicated,
        Long fileId,
        String status
) {
}
