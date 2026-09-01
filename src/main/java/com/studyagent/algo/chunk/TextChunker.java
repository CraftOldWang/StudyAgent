package com.studyagent.algo.chunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本切分器，按指定大小和重叠窗口将解析后的纯文本切成检索 chunk。
 *
 * <p>纯算法实现，不依赖 Spring 容器：窗口大小和重叠量由调用方从配置中读出后显式传入。</p>
 */
public final class TextChunker {

    private TextChunker() {
    }

    /**
     * 生成父 chunk。父块比召回子块更大，主要用于在子块命中后补全文档上下文。
     */
    public static List<String> parentChunks(String rawText, int parentChunkSize, int parentChunkOverlap) {
        return chunk(rawText, parentChunkSize, parentChunkOverlap);
    }

    /**
     * 使用指定窗口切分文本。
     *
     * <p>父子检索需要两套粒度：子 chunk 用较小窗口提高召回精度，父 chunk 用较大窗口承载完整解释。
     * 这里复用同一套断句策略，避免父块和子块的空白清理规则不一致。</p>
     */
    public static List<String> chunk(String rawText, int chunkSize, int chunkOverlap) {
        String text = normalize(rawText);
        if (text.isEmpty()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        int safeChunkSize = Math.max(1, chunkSize);
        int overlap = Math.max(0, Math.min(chunkOverlap, safeChunkSize / 2));
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + safeChunkSize, text.length());
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
     * 统一清理文本中的换行和空白，让父块、子块基于同一份规范化文本切分。
     */
    private static String normalize(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 尽量在段落或句号处截断，减少 chunk 中间切断语义的概率。
     */
    private static int adjustEnd(String text, int start, int end) {
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
