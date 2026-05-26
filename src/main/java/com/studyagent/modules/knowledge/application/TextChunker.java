package com.studyagent.modules.knowledge.application;

import com.studyagent.common.config.RagProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TextChunker {

    private final RagProperties properties;

    public List<String> chunk(String rawText) {
        String text = rawText.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (text.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int chunkSize = properties.chunkSize();
        int overlap = Math.min(properties.chunkOverlap(), chunkSize / 2);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            int adjustedEnd = adjustEnd(text, start, end);
            String chunk = text.substring(start, adjustedEnd).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (adjustedEnd >= text.length()) {
                break;
            }
            start = Math.max(adjustedEnd - overlap, start + 1);
        }
        return chunks;
    }

    private int adjustEnd(String text, int start, int end) {
        if (end >= text.length()) {
            return text.length();
        }
        int newline = text.lastIndexOf('\n', end);
        if (newline > start + 200) {
            return newline;
        }
        int sentence = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('.', end));
        if (sentence > start + 200) {
            return sentence + 1;
        }
        return end;
    }
}
