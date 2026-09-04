package com.studyagent.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AiModelProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DashScopeEmbeddingServiceTest {

    @Test
    void mapsDocumentAndQueryPurposeToOfficialSdkTextType() throws Exception {
        TextEmbedding client = mock(TextEmbedding.class);
        TextEmbeddingResult providerResult = result(0.25, 0.75);
        when(client.call(any(TextEmbeddingParam.class))).thenReturn(providerResult);
        DashScopeEmbeddingService service = new DashScopeEmbeddingService(client, properties());

        assertThat(service.embed("document", EmbeddingPurpose.DOCUMENT)).containsExactly(0.25f, 0.75f);
        assertThat(service.embed("query", EmbeddingPurpose.QUERY)).containsExactly(0.25f, 0.75f);

        ArgumentCaptor<TextEmbeddingParam> captor = ArgumentCaptor.forClass(TextEmbeddingParam.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).call(captor.capture());
        assertThat(captor.getAllValues().get(0).getParameters().get("text_type"))
                .isEqualTo("document");
        assertThat(captor.getAllValues().get(1).getParameters().get("text_type"))
                .isEqualTo("query");
    }

    @Test
    void rejectsMissingPurposeBeforeCallingProvider() {
        DashScopeEmbeddingService service = new DashScopeEmbeddingService(mock(TextEmbedding.class), properties());

        assertThatThrownBy(() -> service.embed("text", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用途");
    }

    private TextEmbeddingResult result(double... values) {
        TextEmbeddingResultItem item = new TextEmbeddingResultItem();
        item.setEmbedding(java.util.Arrays.stream(values).boxed().toList());
        TextEmbeddingOutput output = new TextEmbeddingOutput();
        output.setEmbeddings(List.of(item));
        TextEmbeddingResult result = mock(TextEmbeddingResult.class);
        when(result.getOutput()).thenReturn(output);
        return result;
    }

    private AiModelProperties properties() {
        return new AiModelProperties(
                new AiModelProperties.Embedding(
                        "dashscope", "text-embedding-v3", 1024, "test-key", "https://dashscope.aliyuncs.com"),
                null);
    }
}
