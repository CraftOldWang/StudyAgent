package com.studyagent.algo.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(count);
    }

    @Test
    void textShorterThanOneChunkShouldYieldSingleChunk() {
        List<String> chunks = TextChunker.chunk("hello world", 900, 120);

        assertThat(chunks).containsExactly("hello world");
    }

    @Test
    void blankOrNullTextShouldYieldNoChunks() {
        assertThat(TextChunker.chunk(null, 900, 120)).isEmpty();
        assertThat(TextChunker.chunk("   \n\t ", 900, 120)).isEmpty();
    }

    @Test
    void consecutiveChunksShouldOverlapByRequestedAmount() {
        String text = "abcdefghijklmnopqrstuvwxy";

        List<String> chunks = TextChunker.chunk(text, 10, 3);

        assertThat(chunks).containsExactly("abcdefghij", "hijklmnopq", "opqrstuvwx", "vwxy");
        for (int i = 0; i < chunks.size() - 1; i++) {
            String tail = chunks.get(i).substring(chunks.get(i).length() - 3);
            assertThat(chunks.get(i + 1)).startsWith(tail);
        }
    }

    @Test
    void overlapShouldBeClampedToHalfChunkSizeWhenCallerPassesOverlapAtLeastSize() {
        String text = "abcdefghijklmnopqrstuvwxy";

        List<String> huge = TextChunker.chunk(text, 10, 50);
        List<String> equal = TextChunker.chunk(text, 10, 10);
        List<String> half = TextChunker.chunk(text, 10, 5);

        assertThat(huge).isEqualTo(half);
        assertThat(equal).isEqualTo(half);
        assertThat(huge).containsExactly("abcdefghij", "fghijklmno", "klmnopqrst", "pqrstuvwxy");
        assertThat(huge.get(0)).endsWith(huge.get(1).substring(0, 5));
    }

    @Test
    void negativeOverlapShouldBeTreatedAsZero() {
        String text = "abcdefghijklmnopqrst";

        assertThat(TextChunker.chunk(text, 10, -5)).isEqualTo(TextChunker.chunk(text, 10, 0));
    }

    @Test
    void boundarySnapShouldPreferNewlineNearEndOfWindow() {
        String text = repeat('a', 250) + "\n" + repeat('b', 250);

        List<String> chunks = TextChunker.chunk(text, 300, 0);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo(repeat('a', 250));
        assertThat(chunks.get(1)).isEqualTo(repeat('b', 250));
    }

    @Test
    void boundarySnapShouldPreferNewlineOverSentenceDelimiter() {
        String text = repeat('a', 210) + "\n" + repeat('a', 40) + "。" + repeat('b', 250);

        List<String> chunks = TextChunker.chunk(text, 300, 0);

        assertThat(chunks.get(0)).isEqualTo(repeat('a', 210));
    }

    @Test
    void boundarySnapShouldFallBackToSentenceDelimiterAndKeepIt() {
        List<String> ideographic = TextChunker.chunk(repeat('a', 250) + "。" + repeat('b', 250), 300, 0);
        List<String> ascii = TextChunker.chunk(repeat('a', 250) + "." + repeat('b', 250), 300, 0);

        assertThat(ideographic.get(0)).hasSize(251).endsWith("。");
        assertThat(ideographic.get(1)).isEqualTo(repeat('b', 250));
        assertThat(ascii.get(0)).hasSize(251).endsWith(".");
    }

    @Test
    void boundarySnapShouldNotFireWhenCandidateIsWithin200CharsOfWindowStart() {
        String text = repeat('a', 50) + "\n" + repeat('b', 400);

        List<String> chunks = TextChunker.chunk(text, 300, 0);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(300).contains("\n");
        assertThat(chunks.get(1)).isEqualTo(repeat('b', 151));
    }

    @Test
    void boundarySnapCanNeverFireWhenChunkSizeIsNotGreaterThan200() {
        String text = repeat('a', 100) + "\n" + repeat('b', 300);

        List<String> chunks = TextChunker.chunk(text, 200, 0);

        assertThat(chunks.get(0)).hasSize(200).contains("\n");
    }

    @Test
    void sentenceSnapCanOvershootWindowByOneCharWhenDelimiterSitsExactlyAtWindowEnd() {
        String text = repeat('a', 300) + "." + repeat('b', 200);

        List<String> chunks = TextChunker.chunk(text, 300, 0);

        assertThat(chunks.get(0)).hasSize(301).endsWith(".");
        assertThat(chunks.get(1)).isEqualTo(repeat('b', 200));
    }

    @Test
    void normalizationShouldCollapseLineEndingsBlankLinesAndHorizontalWhitespace() {
        assertThat(TextChunker.chunk("a\r\nb", 900, 0)).containsExactly("a\nb");
        assertThat(TextChunker.chunk("a\rb", 900, 0)).containsExactly("a\nb");
        assertThat(TextChunker.chunk("a  \t  b", 900, 0)).containsExactly("a b");
        assertThat(TextChunker.chunk("a\n\n\n\n\nb", 900, 0)).containsExactly("a\n\nb");
        assertThat(TextChunker.chunk("a\n\nb", 900, 0)).containsExactly("a\n\nb");
        assertThat(TextChunker.chunk("  padded  ", 900, 0)).containsExactly("padded");
    }

    @Test
    void normalizationShouldNotCollapseBlankLinesSeparatedBySpaces() {
        assertThat(TextChunker.chunk("a\n \n \nb", 900, 0)).containsExactly("a\n \n \nb");
        assertThat(TextChunker.chunk("a\t\t\nb", 900, 0)).containsExactly("a \nb");
    }

    @Test
    void parentChunksShouldDelegateToChunkWithGivenWindow() {
        String text = repeat('a', 250) + "。" + repeat('b', 250);

        assertThat(TextChunker.parentChunks(text, 300, 30)).isEqualTo(TextChunker.chunk(text, 300, 30));
    }

    @Test
    void parentChunksShouldContainTheirChildrenWithMonotonicChildIndices() {
        String text = "这是一段用于测试父子切分的中文文本，需要足够长以产生多个父块。".repeat(200);

        List<String> parents = TextChunker.parentChunks(text, 2400, 240);

        assertThat(parents).hasSizeGreaterThan(1);
        int globalChildIndex = 0;
        for (String parent : parents) {
            List<String> children = TextChunker.chunk(parent, 900, 120);
            assertThat(children).isNotEmpty();
            for (String child : children) {
                assertThat(parent).contains(child);
                assertThat(child.length()).isLessThanOrEqualTo(parent.length());
            }
            int previousIndex = globalChildIndex;
            globalChildIndex += children.size();
            assertThat(globalChildIndex).isGreaterThan(previousIndex);
        }
        assertThat(globalChildIndex).isGreaterThanOrEqualTo(parents.size());
    }
}
