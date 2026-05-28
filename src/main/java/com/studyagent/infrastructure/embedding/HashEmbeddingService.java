package com.studyagent.infrastructure.embedding;

import com.studyagent.common.config.ElasticsearchProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 基于哈希的本地 embedding 实现，主要用于开发或测试时不依赖外部模型。
 */
@Service
@RequiredArgsConstructor
public class HashEmbeddingService implements EmbeddingService {

    private final ElasticsearchProperties properties;

    @Override
    public float[] embed(String text) {
        int dimensions = properties.vectorDimensions();
        float[] vector = new float[dimensions];
        String normalized = text == null ? "" : text.toLowerCase();
        String[] terms = normalized.split("[^\\p{IsHan}\\p{IsAlphabetic}\\p{IsDigit}]+");
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            int index = positiveHash(term) % dimensions;
            vector[index] += 1.0f;
        }

        // 额外加入字符级特征，缓解中文文本分词为空或过粗的问题。
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (!Character.isWhitespace(ch)) {
                int index = positiveHash(String.valueOf(ch)) % dimensions;
                vector[index] += 0.15f;
            }
        }

        normalize(vector);
        return vector;
    }

    /**
     * 生成稳定的正整数哈希。
     */
    private int positiveHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            int value = ((hash[0] & 0xff) << 24)
                    | ((hash[1] & 0xff) << 16)
                    | ((hash[2] & 0xff) << 8)
                    | (hash[3] & 0xff);
            return value & 0x7fffffff;
        } catch (Exception ex) {
            return Math.abs(text.hashCode());
        }
    }

    /**
     * 将向量归一化，便于使用 cosine 相似度。
     */
    private void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
