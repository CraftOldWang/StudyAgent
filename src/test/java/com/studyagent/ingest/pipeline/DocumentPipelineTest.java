package com.studyagent.ingest.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.algo.chunk.StructuredChunker;
import com.studyagent.algo.chunk.TokenCounter;
import com.studyagent.algo.chunk.TokenWindowChunker;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AiModelProperties;
import com.studyagent.ingest.parse.DocumentTextParser;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.FileRecord;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import com.studyagent.rag.index.ElasticsearchIndexer;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import com.studyagent.rag.embedding.EmbeddingService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class DocumentPipelineTest {

    @Test
    void runsAllStagesAndCompletesOnlyAfterBulkSuccess() {
        Fixture fixture = fixture();
        when(fixture.persistence().claim(10L, true)).thenReturn(document());
        when(fixture.persistence().loadFile(20L)).thenReturn(file());
        when(fixture.objectStorage().getObject("files/demo.md"))
                .thenReturn(new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)));
        when(fixture.parser().parse(any())).thenReturn("# Heading\n\nbody");
        when(fixture.embeddingService().embed(any(), eq(EmbeddingPurpose.DOCUMENT)))
                .thenReturn(new float[]{0.1f, 0.2f});

        boolean processed = fixture.pipeline().process(10L);

        assertThat(processed).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ElasticsearchChunkDocument>> indexed = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(fixture.persistence(), fixture.elasticsearchIndexer());
        order.verify(fixture.persistence()).markParsed(10L);
        order.verify(fixture.persistence()).replaceChunks(eq(10L), anyList());
        order.verify(fixture.persistence()).markEmbeddingCompleted(eq(10L), anyList());
        order.verify(fixture.elasticsearchIndexer()).bulkIndex(indexed.capture());
        order.verify(fixture.persistence()).markCompleted(eq(10L), anyList());
        assertThat(indexed.getValue()).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.userId()).isEqualTo("30");
            assertThat(chunk.knowledgeBaseId()).isEqualTo("40");
            assertThat(chunk.documentId()).isEqualTo("10");
            assertThat(chunk.chunkerVersion()).isEqualTo(DocumentPipeline.CHUNKER_VERSION);
            assertThat(chunk.embeddingModel()).isEqualTo("text-embedding-v3");
        });
    }

    @Test
    void recordsFailedStageAndRethrowsOriginalFailure() {
        Fixture fixture = fixture();
        when(fixture.persistence().claim(10L, true)).thenReturn(document());
        when(fixture.persistence().loadFile(20L)).thenReturn(file());
        when(fixture.objectStorage().getObject("files/demo.md"))
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        BusinessException failure = new BusinessException("bad pdf");
        when(fixture.parser().parse(any())).thenThrow(failure);

        assertThatThrownBy(() -> fixture.pipeline().process(10L)).isSameAs(failure);

        verify(fixture.persistence()).markFailed(10L, PipelineStatus.PARSING, failure);
        verify(fixture.elasticsearchIndexer(), never()).bulkIndex(anyList());
    }

    @Test
    void deterministicChunkIdUsesFullSha256() {
        DocumentPipeline pipeline = fixture().pipeline();

        String first = pipeline.chunkId(10L, "CHILD", 3, "abc");
        String repeated = pipeline.chunkId(10L, "CHILD", 3, "abc");
        String changed = pipeline.chunkId(10L, "CHILD", 4, "abc");

        assertThat(first).matches("[0-9a-f]{64}").isEqualTo(repeated).isNotEqualTo(changed);
    }

    private Fixture fixture() {
        DocumentPipelinePersistence persistence = mock(DocumentPipelinePersistence.class);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        DocumentTextParser parser = mock(DocumentTextParser.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        ElasticsearchIndexer elasticsearchIndexer = mock(ElasticsearchIndexer.class);
        TokenCounter tokenCounter = String::length;
        DocumentPipeline pipeline = new DocumentPipeline(
                persistence,
                objectStorage,
                parser,
                new StructuredChunker(tokenCounter),
                new TokenWindowChunker(tokenCounter),
                embeddingService,
                elasticsearchIndexer,
                new AiModelProperties(
                        new AiModelProperties.Embedding(
                                "dashscope",
                                "text-embedding-v3",
                                2,
                                "unused",
                                "http://localhost"),
                        null),
                new ObjectMapper());
        return new Fixture(pipeline, persistence, objectStorage, parser, embeddingService, elasticsearchIndexer);
    }

    private Document document() {
        Document document = new Document();
        document.setId(10L);
        document.setFileRecordId(20L);
        document.setUserId(30L);
        document.setKnowledgeBaseId(40L);
        document.setPipelineStatus(PipelineStatus.PARSING.name());
        return document;
    }

    private FileRecord file() {
        FileRecord file = new FileRecord();
        file.setId(20L);
        file.setStorageKey("files/demo.md");
        return file;
    }

    private record Fixture(
            DocumentPipeline pipeline,
            DocumentPipelinePersistence persistence,
            ObjectStorageService objectStorage,
            DocumentTextParser parser,
            EmbeddingService embeddingService,
            ElasticsearchIndexer elasticsearchIndexer
    ) {
    }
}
