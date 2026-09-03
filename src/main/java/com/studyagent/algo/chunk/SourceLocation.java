package com.studyagent.algo.chunk;

import java.util.List;
import java.util.Objects;

/**
 * Chunk 在解析器输出文本中的来源坐标。
 */
public record SourceLocation(
        int startInclusive,
        int endExclusive,
        List<String> headingPath
) {

    public SourceLocation {
        if (startInclusive < 0 || endExclusive < startInclusive) {
            throw new IllegalArgumentException("source offsets must satisfy 0 <= start <= end");
        }
        headingPath = List.copyOf(Objects.requireNonNull(headingPath, "headingPath"));
    }
}
