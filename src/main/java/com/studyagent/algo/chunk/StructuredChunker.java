package com.studyagent.algo.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 Markdown 结构边界优先切分，单个超长结构块再降级为 token window。
 */
public final class StructuredChunker {

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*#*[ \\t]*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-+*]|\\d+[.)])[ \\t]+.+$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$"
    );

    private final TokenCounter tokenCounter;
    private final TokenWindowChunker tokenWindowChunker;

    public StructuredChunker(TokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.tokenWindowChunker = new TokenWindowChunker(tokenCounter);
    }

    public List<ChunkSegment> childChunks(String parsedText) {
        return chunk(parsedText, TokenWindowChunker.CHILD_MAX_TOKENS, TokenWindowChunker.CHILD_OVERLAP_TOKENS);
    }

    public List<ChunkSegment> parentChunks(String parsedText) {
        return chunk(parsedText, TokenWindowChunker.PARENT_MAX_TOKENS, TokenWindowChunker.PARENT_OVERLAP_TOKENS);
    }

    public List<ChunkSegment> chunk(String parsedText, int maxTokens, int overlapTokens) {
        if (parsedText == null || parsedText.isBlank()) {
            return List.of();
        }

        List<Line> lines = lines(parsedText);
        List<Heading> headings = new ArrayList<>();
        List<ChunkSegment> segments = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            Line line = lines.get(index);
            if (line.text().isBlank()) {
                index++;
                continue;
            }

            Matcher heading = HEADING.matcher(line.text());
            int endIndex;
            if (heading.matches()) {
                updateHeadingPath(headings, heading.group(1).length(), heading.group(2).strip());
                endIndex = index + 1;
            } else if (fenceMarker(line.text()) != null) {
                endIndex = fencedBlockEnd(lines, index, fenceMarker(line.text()));
            } else if (isTableStart(lines, index)) {
                endIndex = tableEnd(lines, index);
            } else if (LIST_ITEM.matcher(line.text()).matches()) {
                endIndex = listEnd(lines, index);
            } else {
                endIndex = paragraphEnd(lines, index);
            }

            addSegment(parsedText, line.start(), lines.get(endIndex - 1).end(), headings,
                    maxTokens, overlapTokens, segments);
            index = endIndex;
        }
        return List.copyOf(segments);
    }

    private void addSegment(
            String parsedText,
            int start,
            int end,
            List<Heading> headings,
            int maxTokens,
            int overlapTokens,
            List<ChunkSegment> target
    ) {
        String content = parsedText.substring(start, end);
        ChunkSegment segment = new ChunkSegment(
                content,
                tokenCounter.count(content),
                new SourceLocation(start, end, headings.stream().map(Heading::title).toList())
        );
        target.addAll(tokenWindowChunker.split(segment, maxTokens, overlapTokens));
    }

    private void updateHeadingPath(List<Heading> path, int level, String heading) {
        while (!path.isEmpty() && path.getLast().level() >= level) {
            path.removeLast();
        }
        path.add(new Heading(level, heading));
    }

    private int fencedBlockEnd(List<Line> lines, int start, String marker) {
        for (int index = start + 1; index < lines.size(); index++) {
            if (lines.get(index).text().stripLeading().startsWith(marker)) {
                return index + 1;
            }
        }
        return lines.size();
    }

    private int tableEnd(List<Line> lines, int start) {
        int index = start + 2;
        while (index < lines.size() && !lines.get(index).text().isBlank()
                && lines.get(index).text().contains("|")) {
            index++;
        }
        return index;
    }

    private int listEnd(List<Line> lines, int start) {
        int index = start + 1;
        while (index < lines.size() && LIST_ITEM.matcher(lines.get(index).text()).matches()) {
            index++;
        }
        return index;
    }

    private int paragraphEnd(List<Line> lines, int start) {
        int index = start + 1;
        while (index < lines.size()) {
            String text = lines.get(index).text();
            if (text.isBlank() || HEADING.matcher(text).matches() || fenceMarker(text) != null
                    || LIST_ITEM.matcher(text).matches() || isTableStart(lines, index)) {
                break;
            }
            index++;
        }
        return index;
    }

    private boolean isTableStart(List<Line> lines, int index) {
        return index + 1 < lines.size()
                && lines.get(index).text().contains("|")
                && TABLE_SEPARATOR.matcher(lines.get(index + 1).text()).matches();
    }

    private String fenceMarker(String line) {
        String stripped = line.stripLeading();
        if (stripped.startsWith("```")) {
            return "```";
        }
        if (stripped.startsWith("~~~")) {
            return "~~~";
        }
        return null;
    }

    private List<Line> lines(String text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            int contentEnd = newline < 0 ? end : newline;
            if (contentEnd > start && text.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            lines.add(new Line(start, end, text.substring(start, contentEnd)));
            start = end;
        }
        return lines;
    }

    private record Line(int start, int end, String text) {
    }

    private record Heading(int level, String title) {
    }
}
