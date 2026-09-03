package com.studyagent.modules.evaluation.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/**
 * 一条 RAG 检索评测样本。
 *
 * @param question 问题文本。
 * @param expectedChunkIds 人工或半自动标注的正确证据 chunkId，Recall 只依赖这个字段。
 * @param expectedAnswer 可选参考答案，当前 Recall 评测不使用，后续可扩展答案质量评测。
 * @param metadata 额外标签，例如 difficulty/source/documentTitle。
 */
public record RagEvalCase(
        @NotBlank String question,
        @NotEmpty List<String> expectedChunkIds,
        String expectedAnswer,
        Map<String, Object> metadata
) {
}
