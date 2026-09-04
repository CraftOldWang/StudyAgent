package com.studyagent.rag.embedding;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AiModelProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashScopeEmbeddingService implements EmbeddingService {

    private final TextEmbedding textEmbedding;
    private final AiModelProperties properties;

    @Override
    public float[] embed(String text, EmbeddingPurpose purpose) {
        if (text == null || text.isBlank()) {
            throw new BusinessException("向量化文本不能为空");
        }
        if (purpose == null) {
            throw new BusinessException("向量化用途不能为空");
        }

        AiModelProperties.Embedding embedding = properties.embedding();
        TextEmbeddingParam param = TextEmbeddingParam.builder()
                .apiKey(embedding.apiKey())
                .model(embedding.model())
                .texts(List.of(text))
                .textType(textType(purpose))
                .dimension(embedding.dimensions())
                .build();
        try {
            return vector(textEmbedding.call(param));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("DashScope embedding 调用失败: " + ex.getMessage());
        }
    }

    private TextEmbeddingParam.TextType textType(EmbeddingPurpose purpose) {
        return switch (purpose) {
            case DOCUMENT -> TextEmbeddingParam.TextType.DOCUMENT;
            case QUERY -> TextEmbeddingParam.TextType.QUERY;
        };
    }

    private float[] vector(TextEmbeddingResult result) {
        if (result == null || result.getOutput() == null) {
            throw new BusinessException("DashScope embedding 未返回结果");
        }
        List<TextEmbeddingResultItem> items = result.getOutput().getEmbeddings();
        if (items == null || items.size() != 1 || items.getFirst().getEmbedding() == null
                || items.getFirst().getEmbedding().isEmpty()) {
            throw new BusinessException("DashScope embedding 返回空向量");
        }
        List<Double> values = items.getFirst().getEmbedding();
        float[] vector = new float[values.size()];
        for (int index = 0; index < values.size(); index++) {
            vector[index] = values.get(index).floatValue();
        }
        return vector;
    }
}
