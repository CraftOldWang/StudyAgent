package com.studyagent.modules.evaluation.application;

import java.util.List;

/**
 * DeepSeek 生成的临时评测集。
 *
 * <p>不强制入库，调用方可以直接把 cases 作为 Recall 评测接口的输入。
 * sourceChunks 会一起返回，便于人工快速检查“问题是否真的对应这些 chunk”。</p>
 */
public record GeneratedRagEvalDataset(
        List<RagEvalCase> cases,
        List<SourceChunk> sourceChunks,
        List<String> warnings
) {

    /**
     * 生成问题时提供给模型的 chunk 摘要。
     */
    public record SourceChunk(
            Long chunkId,
            Long documentId,
            Long knowledgeBaseId,
            Long parentChunkId,
            String chunkType,
            Integer chunkIndex,
            String documentTitle,
            String content
    ) {
    }
}
