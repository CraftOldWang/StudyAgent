package com.studyagent.algo.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredChunkerTest {

    private final TokenCounter tokenCounter = new JtokkitTokenCounter();
    private final StructuredChunker chunker = new StructuredChunker(tokenCounter);

    @Test
    void shouldSplitMarkdownStructuresAndTrackHeadingPath() {
        String markdown = """
                # Course

                Intro paragraph.
                second line.

                - one
                - two

                ```java
                int value = 1;
                ```

                | A | B |
                |---|---|
                | 1 | 2 |

                ## Detail
                Tail paragraph.
                """;

        List<ChunkSegment> segments = chunker.childChunks(markdown);

        assertThat(segments).extracting(ChunkSegment::content).containsExactly(
                "# Course\n",
                "Intro paragraph.\nsecond line.\n",
                "- one\n- two\n",
                "```java\nint value = 1;\n```\n",
                "| A | B |\n|---|---|\n| 1 | 2 |\n",
                "## Detail\n",
                "Tail paragraph.\n"
        );
        assertThat(segments.get(0).sourceLocation().headingPath()).containsExactly("Course");
        assertThat(segments.get(4).sourceLocation().headingPath()).containsExactly("Course");
        assertThat(segments.get(5).sourceLocation().headingPath()).containsExactly("Course", "Detail");
        assertThat(segments.get(6).sourceLocation().headingPath()).containsExactly("Course", "Detail");
        assertSegmentsPointToOriginalText(markdown, segments);
    }

    @Test
    void shouldFallbackOnlyOversizedStructureAndPreserveItsHeadingAndOffsets() {
        String markdown = "# Topic\n\n" + "token ".repeat(80);

        List<ChunkSegment> segments = chunker.chunk(markdown, 20, 5);

        assertThat(segments.getFirst().content()).isEqualTo("# Topic\n");
        assertThat(segments.subList(1, segments.size())).hasSizeGreaterThan(1);
        assertThat(segments.subList(1, segments.size()))
                .allSatisfy(segment -> {
                    assertThat(segment.tokenCount()).isLessThanOrEqualTo(20);
                    assertThat(segment.sourceLocation().headingPath()).containsExactly("Topic");
                });
        assertSegmentsPointToOriginalText(markdown, segments);
    }

    @Test
    void shouldReturnNoSegmentsForMissingText() {
        assertThat(chunker.childChunks(null)).isEmpty();
        assertThat(chunker.parentChunks(" \r\n\t")).isEmpty();
    }

    @Test
    void packsShortBlocksWithTitlesWithoutCrossingSections() {
        String text = "# Course\n\nIntro.\n\n- first\n- second\n\n## Next\n\nDefinition.\n\nExample.\n";
        List<ChunkSegment> parents = chunker.parentChunks(text, 80);
        assertThat(parents).hasSize(2);
        assertThat(parents.getFirst().content()).contains("# Course", "Intro.", "- second").doesNotContain("## Next");
        assertThat(parents.getLast().content()).contains("## Next", "Definition.", "Example.");
        assertThat(parents.getLast().sourceLocation().headingPath()).containsExactly("Course", "Next");
        assertSegmentsPointToOriginalText(text, parents);
    }

    @Test
    void longBodyRetainsItsTitleAndAllTextWithinTokenLimit() {
        String text = "# Topic\n\n" + "longword ".repeat(100) + "结尾😀";
        List<ChunkSegment> parents = chunker.parentChunks(text, 20);
        assertThat(parents).hasSizeGreaterThan(1);
        assertThat(parents.getFirst().content()).contains("# Topic", "longword");
        assertThat(parents).allSatisfy(parent -> assertThat(parent.tokenCount()).isLessThanOrEqualTo(20));
        assertThat(parents.stream().map(ChunkSegment::content).collect(java.util.stream.Collectors.joining()))
                .isEqualTo(text);
        assertSegmentsPointToOriginalText(text, parents);
    }

    @Test
    void preservesSmallCodeAndTableBlocksWhilePackingParagraphs() {
        String text = "# Lesson\n\nIntro.\n\n```java\nint a = 1;\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |\n";
        List<ChunkSegment> parents = chunker.parentChunks(text, 100);
        assertThat(parents).hasSize(1);
        assertThat(parents.getFirst().content()).isEqualTo(text);
    }

    private void assertSegmentsPointToOriginalText(String source, List<ChunkSegment> segments) {
        for (ChunkSegment segment : segments) {
            SourceLocation location = segment.sourceLocation();
            assertThat(segment.content()).isEqualTo(source.substring(location.startInclusive(), location.endExclusive()));
            assertThat(segment.tokenCount()).isEqualTo(tokenCounter.count(segment.content()));
        }
    }
}
