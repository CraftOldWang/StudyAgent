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
import com.studyagent.config.RagProperties;
import com.studyagent.config.ElasticsearchProperties;
import com.studyagent.ingest.parse.DocumentTextParser;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.FileRecord;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import com.studyagent.rag.index.ElasticsearchIndexer;
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
        when(fixture.embeddingArtifacts().loadOrCreate(eq(30L), any()))
                .thenReturn(new float[]{0.1f, 0.2f});

        boolean processed = fixture.pipeline().process(10L);

        assertThat(processed).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ElasticsearchChunkDocument>> indexed = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(fixture.persistence(), fixture.elasticsearchIndexer());
        order.verify(fixture.persistence()).markParsed(any(Document.class), any(), any());
        order.verify(fixture.persistence()).replaceChunks(any(Document.class), anyList(), eq(fixture.pipeline().chunkerVersion()));
        order.verify(fixture.persistence()).markEmbeddingCompleted(any(Document.class));
        order.verify(fixture.elasticsearchIndexer()).bulkIndexAcknowledged(indexed.capture());
        order.verify(fixture.persistence()).markCompleted(any(Document.class));
        assertThat(indexed.getValue()).isNotEmpty().allSatisfy(chunk -> {
            assertThat(chunk.userId()).isEqualTo("30");
            assertThat(chunk.knowledgeBaseId()).isEqualTo("40");
            assertThat(chunk.documentId()).isEqualTo("10");
            assertThat(chunk.chunkerVersion()).isEqualTo(fixture.pipeline().chunkerVersion());
            assertThat(chunk.embeddingModel()).isEqualTo("text-embedding-v3");
        });
        assertThat(indexed.getValue()).hasSize(2);
        assertThat(indexed.getValue().getFirst().embedding()).isNull();
        assertThat(indexed.getValue().getLast().embedding()).containsExactly(0.1f, 0.2f);
        verify(fixture.embeddingArtifacts()).loadOrCreate(eq(30L), any());
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

        verify(fixture.persistence()).markFailed(any(Document.class), eq(PipelineStatus.PARSING), eq(failure));
        verify(fixture.elasticsearchIndexer(), never()).bulkIndexAcknowledged(anyList());
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
        return fixture(new RagProperties(6, 800, 80, 2400, 0, 30, 30, 60, RagProperties.ChunkStrategy.STRUCTURED));
    }

    private Fixture fixture(RagProperties ragProperties) {
        DocumentPipelinePersistence persistence = mock(DocumentPipelinePersistence.class);
        ObjectStorageService objectStorage = mock(ObjectStorageService.class);
        DocumentTextParser parser = mock(DocumentTextParser.class);
        DocumentEmbeddingArtifacts embeddingArtifacts = mock(DocumentEmbeddingArtifacts.class);
        ElasticsearchIndexer elasticsearchIndexer = mock(ElasticsearchIndexer.class);
        when(elasticsearchIndexer.bulkIndexAcknowledged(anyList())).thenAnswer(invocation -> {
            List<ElasticsearchChunkDocument> documents = invocation.getArgument(0);
            return new ElasticsearchIndexer.BulkIndexResult(documents.stream().map(ElasticsearchChunkDocument::chunkId).toList(), List.of());
        });
        TokenCounter tokenCounter = String::length;
        DocumentPipeline pipeline = new DocumentPipeline(
                persistence,
                objectStorage,
                parser,
                new StructuredChunker(tokenCounter),
                new TokenWindowChunker(tokenCounter),
                embeddingArtifacts,
                elasticsearchIndexer,
                new AiModelProperties(
                        new AiModelProperties.Embedding(
                                "dashscope",
                                "text-embedding-v3",
                                2,
                                "unused",
                                "http://localhost"),
                        null),
                new ObjectMapper(), ragProperties, new ElasticsearchProperties("http://localhost", "test-v1", "test-read", "test-write", 2));
        return new Fixture(pipeline, persistence, objectStorage, parser, embeddingArtifacts, elasticsearchIndexer);
    }

    @Test
    void configuredWindowsControlActualChunksAndVersionedIds() {
        Fixture fixture = fixture(new RagProperties(6, 6, 1, 12, 0, 30, 30, 60, RagProperties.ChunkStrategy.FIXED));
        when(fixture.persistence().claim(10L, true)).thenReturn(document());
        when(fixture.persistence().loadFile(20L)).thenReturn(file());
        when(fixture.objectStorage().getObject("files/demo.md")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(fixture.parser().parse(any())).thenReturn("abcdefghijklmno pqrstuvwxyz");
        when(fixture.embeddingArtifacts().loadOrCreate(eq(30L), any())).thenReturn(new float[]{0.1f, 0.2f});
        fixture.pipeline().process(10L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ElasticsearchChunkDocument>> indexed = ArgumentCaptor.forClass(List.class);
        verify(fixture.elasticsearchIndexer()).bulkIndexAcknowledged(indexed.capture());
        assertThat(indexed.getValue()).allSatisfy(chunk -> assertThat(chunk.content().length())
                .isLessThanOrEqualTo("PARENT".equals(chunk.chunkType()) ? 12 : 6));
        assertThat(fixture.pipeline().chunkerVersion()).contains("fixed", "c6o1", "p12o0");
        assertThat(fixture.pipeline().chunkId(10L, "CHILD", 0, "same-content"))
                .isNotEqualTo(fixture().pipeline().chunkId(10L, "CHILD", 0, "same-content"));
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

    @Test
    void resumedIndexingReusesParsedTextAndChunksAndSkipsAcknowledgedParent() throws Exception {
        Fixture fixture = fixture();
        Document document = document();
        String text = "# Heading\n\nbody";
        document.setParserVersion(DocumentPipeline.PARSER_VERSION);
        document.setParsedTextKey("parsed/test.txt");
        document.setParsedTextHash(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))));
        document.setChunkerVersion(fixture.pipeline().chunkerVersion());
        document.setIndexTarget("test-v1:text-embedding-v3:2");
        when(fixture.persistence().claim(10L, true)).thenReturn(document);
        when(fixture.objectStorage().getObject("parsed/test.txt"))
                .thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        DocumentChunk parent = new DocumentChunk();
        parent.setDocumentId(10L);
        parent.setChunkId("parent");
        parent.setChunkType("PARENT");
        parent.setChunkIndex(0);
        parent.setIndexedAt(java.time.LocalDateTime.now());
        DocumentChunk child = new DocumentChunk();
        child.setDocumentId(10L);
        child.setChunkId("child");
        child.setChunkType("CHILD");
        child.setChunkIndex(0);
        when(fixture.persistence().loadChunks(10L)).thenReturn(List.of(parent, child));
        when(fixture.embeddingArtifacts().loadOrCreate(30L, child)).thenReturn(new float[]{0.1f, 0.2f});
        assertThat(fixture.pipeline().process(10L)).isTrue();
        org.mockito.Mockito.verifyNoInteractions(fixture.parser());
        verify(fixture.persistence(), never()).replaceChunks(any(), anyList(), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ElasticsearchChunkDocument>> indexed = ArgumentCaptor.forClass(List.class);
        verify(fixture.elasticsearchIndexer()).bulkIndexAcknowledged(indexed.capture());
        assertThat(indexed.getValue()).extracting(ElasticsearchChunkDocument::chunkId).containsExactly("child");
        verify(fixture.persistence()).markIndexed(document, List.of("child"));
    }

    @Test
    void partialBulkSuccessIsSavedBeforeFailureAndNeverCompletesDocument() {
        Fixture fixture = fixture();
        when(fixture.persistence().claim(10L, true)).thenReturn(document());
        when(fixture.persistence().loadFile(20L)).thenReturn(file());
        when(fixture.objectStorage().getObject("files/demo.md")).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(fixture.parser().parse(any())).thenReturn("body");
        when(fixture.embeddingArtifacts().loadOrCreate(eq(30L), any())).thenReturn(new float[]{0.1f, 0.2f});
        when(fixture.elasticsearchIndexer().bulkIndexAcknowledged(anyList()))
                .thenReturn(new ElasticsearchIndexer.BulkIndexResult(List.of("parent"), List.of("child: 429")));
        assertThatThrownBy(() -> fixture.pipeline().process(10L)).hasMessageContaining("部分 chunk 索引失败");
        InOrder order = inOrder(fixture.persistence());
        order.verify(fixture.persistence()).markIndexed(any(Document.class), eq(List.of("parent")));
        order.verify(fixture.persistence()).markFailed(any(Document.class), eq(PipelineStatus.INDEXING), any());
        verify(fixture.persistence(), never()).markCompleted(any());
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
            DocumentEmbeddingArtifacts embeddingArtifacts,
            ElasticsearchIndexer elasticsearchIndexer
    ) {
    }
}
