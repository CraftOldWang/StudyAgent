package com.studyagent.algo.chunk;

import java.util.Objects;

/**
 * 带统一 token 数与原文坐标的分块结果。
 */
public record ChunkSegment(
        String content,
        int tokenCount,
        SourceLocation sourceLocation
) {

    public ChunkSegment {
        content = Objects.requireNonNull(content, "content");
        sourceLocation = Objects.requireNonNull(sourceLocation, "sourceLocation");
        if (tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must not be negative");
        }
        if (sourceLocation.endExclusive() - sourceLocation.startInclusive() != content.length()) {
            throw new IllegalArgumentException("source offsets must span the exact segment content");
        }
    }
}
