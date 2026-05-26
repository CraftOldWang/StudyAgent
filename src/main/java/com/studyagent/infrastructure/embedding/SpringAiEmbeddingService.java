package com.studyagent.infrastructure.embedding;

import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.studyagent.common.exception.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
@RequiredArgsConstructor
public class SpringAiEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    @Override
    public float[] embed(String text) {
        return embedWithTextType(text, "document");
    }

    @Override
    public float[] embedQuery(String text) {
        return embedWithTextType(text, "query");
    }

    private float[] embedWithTextType(String text, String textType) {
        if (text == null || text.isBlank()) {
            throw new BusinessException("向量化文本不能为空");
        }
        EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(
                List.of(text),
                DashScopeEmbeddingOptions.builder()
                        .textType(textType)
                        .build()
        ));
        if (response == null || response.getResult() == null) {
            throw new BusinessException("Embedding 模型未返回结果");
        }
        float[] vector = response.getResult().getOutput();
        if (vector == null || vector.length == 0) {
            throw new BusinessException("Embedding 模型返回空向量");
        }
        return vector;
    }
}
