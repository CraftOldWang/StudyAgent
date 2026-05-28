package com.studyagent.modules.knowledge.application;

import com.studyagent.common.config.RagProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 文本切分器，按配置大小和重叠窗口将解析后的纯文本切成检索 chunk。
 */
@Component
@RequiredArgsConstructor
public class TextChunker {

    private final RagProperties properties;

    /**
     * 标准化空白字符后切分文本，返回可直接入库和向量化的 chunk 列表。
     */
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
            // 使用重叠窗口保留跨 chunk 的上下文，start + 1 防止异常配置导致死循环。
            start = Math.max(adjustedEnd - overlap, start + 1);
        }
        return chunks;
    }

    /**
     * 尽量在段落或句号处截断，减少 chunk 中间切断语义的概率。
     */
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
