package com.studyagent.modules.evaluation.application;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * DeepSeek 自动生成 Recall 评测集的请求。
 *
 * @param userId 用户范围，不传时使用默认演示用户。
 * @param knowledgeBaseIds 知识库范围。
 * @param documentIds 可选文档范围；为空时从知识库内已索引 chunk 中抽样。
 * @param caseCount 希望生成的问题数量。
 * @param maxSourceChunks 本次提示词最多投喂多少个 chunk，避免上下文过长。
 * @param maxChunkChars 单个 chunk 最多截取多少字符。
 * @param indexedOnly 是否只使用已经写入 ES 的 chunk，默认 true。
 */
public record RagEvalCaseGenerationRequest(
        Long userId,
        @NotEmpty List<Long> knowledgeBaseIds,
        List<Long> documentIds,
        Integer caseCount,
        Integer maxSourceChunks,
        Integer maxChunkChars,
        Boolean indexedOnly
) {
}
