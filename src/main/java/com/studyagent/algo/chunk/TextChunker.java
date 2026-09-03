package com.studyagent.algo.chunk;

import java.util.List;

/**
 * 旧 pipeline 的临时兼容入口；核心分块逻辑由新 chunker 唯一实现。
 */
public final class TextChunker {

    private static final StructuredChunker DELEGATE = new StructuredChunker(new JtokkitTokenCounter());

    private TextChunker() {
    }

    public static List<String> parentChunks(String rawText, int parentChunkSize, int parentChunkOverlap) {
        return contents(DELEGATE.chunk(rawText, parentChunkSize, parentChunkOverlap));
    }

    public static List<String> chunk(String rawText, int chunkSize, int chunkOverlap) {
        return contents(DELEGATE.chunk(rawText, chunkSize, chunkOverlap));
    }

    private static List<String> contents(List<ChunkSegment> segments) {
        return segments.stream().map(ChunkSegment::content).toList();
    }
}
