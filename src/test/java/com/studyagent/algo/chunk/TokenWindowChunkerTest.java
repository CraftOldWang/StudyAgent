package com.studyagent.algo.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TokenWindowChunkerTest {

    private final TokenCounter tokenCounter = new JtokkitTokenCounter();
    private final TokenWindowChunker chunker = new TokenWindowChunker(tokenCounter);

    @Test
    void shouldRespectTokenLimitOverlapAndOriginalOffsets() {
        String content = "token ".repeat(80) + "结尾😀";
        int sourceStart = 100;
        ChunkSegment source = new ChunkSegment(
                content,
                tokenCounter.count(content),
                new SourceLocation(sourceStart, sourceStart + content.length(), List.of("章节"))
        );

        List<ChunkSegment> chunks = chunker.split(source, 20, 5);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (ChunkSegment chunk : chunks) {
            assertThat(chunk.tokenCount()).isEqualTo(tokenCounter.count(chunk.content())).isLessThanOrEqualTo(20);
            int relativeStart = chunk.sourceLocation().startInclusive() - sourceStart;
            int relativeEnd = chunk.sourceLocation().endExclusive() - sourceStart;
            assertThat(chunk.content()).isEqualTo(content.substring(relativeStart, relativeEnd));
            assertThat(chunk.sourceLocation().headingPath()).containsExactly("章节");
        }
        for (int index = 0; index < chunks.size() - 1; index++) {
            ChunkSegment current = chunks.get(index);
            ChunkSegment next = chunks.get(index + 1);
            int overlapStart = next.sourceLocation().startInclusive() - sourceStart;
            int overlapEnd = current.sourceLocation().endExclusive() - sourceStart;
            assertThat(overlapStart).isLessThan(overlapEnd);
            assertThat(tokenCounter.count(content.substring(overlapStart, overlapEnd)))
                    .isPositive()
                    .isLessThanOrEqualTo(5);
        }
    }

    @Test
    void shouldKeepShortSegmentWithoutChangingItsCoordinates() {
        String content = "short text";
        SourceLocation location = new SourceLocation(7, 7 + content.length(), List.of("A"));
        ChunkSegment source = new ChunkSegment(content, tokenCounter.count(content), location);

        assertThat(chunker.childChunks(source))
                .containsExactly(new ChunkSegment(content, tokenCounter.count(content), location));
    }

    @Test
    void shouldApplyApprovedChildAndParentWindowSizes() {
        String content = "token ".repeat(2600);
        ChunkSegment source = new ChunkSegment(
                content,
                tokenCounter.count(content),
                new SourceLocation(0, content.length(), List.of())
        );

        assertThat(chunker.childChunks(source))
                .hasSizeGreaterThan(1)
                .allSatisfy(segment -> assertThat(segment.tokenCount())
                        .isLessThanOrEqualTo(TokenWindowChunker.CHILD_MAX_TOKENS));
        assertThat(chunker.parentChunks(source))
                .hasSizeGreaterThan(1)
                .allSatisfy(segment -> assertThat(segment.tokenCount())
                        .isLessThanOrEqualTo(TokenWindowChunker.PARENT_MAX_TOKENS));
    }

    @Test
    void shouldRejectInvalidWindowConfiguration() {
        ChunkSegment source = new ChunkSegment("text", 1, new SourceLocation(0, 4, List.of()));

        assertThatThrownBy(() -> chunker.split(source, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.split(source, 10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chunker.split(source, 10, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
