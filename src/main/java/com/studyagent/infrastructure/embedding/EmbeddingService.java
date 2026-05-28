package com.studyagent.infrastructure.embedding;

/**
 * 向量化服务接口，屏蔽具体 embedding provider。
 */
public interface EmbeddingService {

    /**
     * 将文档文本转换为向量。
     */
    float[] embed(String text);

    /**
     * 将查询文本转换为向量；默认与文档向量化共用实现。
     */
    default float[] embedQuery(String text) {
        return embed(text);
    }
}
