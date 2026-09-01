package com.studyagent.modules.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.studyagent.infrastructure.embedding.EmbeddingService;
import com.studyagent.infrastructure.search.ElasticsearchChunkIndexer;
import com.studyagent.infrastructure.search.IndexedChunk;
import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.domain.DocumentChunk;
import com.studyagent.modules.knowledge.infrastructure.DocumentChunkMapper;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentChunkIndexSyncServiceTest {

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentChunkMapper documentChunkMapper;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private ElasticsearchChunkIndexer elasticsearchChunkIndexer;

    @InjectMocks
    private DocumentChunkIndexSyncService syncService;

    @Test
    void syncChunkShouldSkipAlreadyIndexedChunk() {
        DocumentChunk chunk = chunk();
        chunk.setEsDocId("1");
        when(documentChunkMapper.selectById(1L)).thenReturn(chunk);

        boolean synced = syncService.syncChunk(1L);

        assertThat(synced).isFalse();
        verify(embeddingService, never()).embed(any());
        verify(elasticsearchChunkIndexer, never()).index(any());
    }

    @Test
    void syncChunkShouldIndexMissingChunkAndMarkDocumentIndexedWhenComplete() {
        DocumentChunk chunk = chunk();
        Document document = document();
        when(documentChunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(embeddingService.embed("chunk content")).thenReturn(new float[]{0.1f, 0.2f});
        when(elasticsearchChunkIndexer.index(any(IndexedChunk.class))).thenReturn("1");
        when(documentChunkMapper.countByDocumentId(10L)).thenReturn(1);
        when(documentChunkMapper.countMissingEsDocIdByDocumentId(10L)).thenReturn(0);

        boolean synced = syncService.syncChunk(1L);

        assertThat(synced).isTrue();
        ArgumentCaptor<IndexedChunk> indexedChunk = ArgumentCaptor.forClass(IndexedChunk.class);
        verify(elasticsearchChunkIndexer).index(indexedChunk.capture());
        assertThat(indexedChunk.getValue().documentTitle()).isEqualTo("demo");
        assertThat(indexedChunk.getValue().chunkType()).isEqualTo(DocumentChunk.TYPE_CHILD);
        verify(documentChunkMapper).updateEsDocIdIfMissing(1L, "1");
        verify(documentMapper).updateById(document);
        assertThat(document.getIndexStatus()).isEqualTo("INDEXED");
        assertThat(document.getErrorMessage()).isNull();
    }

    @Test
    void syncChunkShouldIndexParentChunk() {
        DocumentChunk chunk = chunk();
        chunk.setChunkType(DocumentChunk.TYPE_PARENT);
        chunk.setParentChunkId(null);
        when(documentChunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(10L)).thenReturn(document());
        when(embeddingService.embed("chunk content")).thenReturn(new float[]{0.1f, 0.2f});
        when(elasticsearchChunkIndexer.index(any(IndexedChunk.class))).thenReturn("1");
        when(documentChunkMapper.countByDocumentId(10L)).thenReturn(1);
        when(documentChunkMapper.countMissingEsDocIdByDocumentId(10L)).thenReturn(0);

        boolean synced = syncService.syncChunk(1L);

        assertThat(synced).isTrue();
        ArgumentCaptor<IndexedChunk> indexedChunk = ArgumentCaptor.forClass(IndexedChunk.class);
        verify(elasticsearchChunkIndexer).index(indexedChunk.capture());
        assertThat(indexedChunk.getValue().chunkType()).isEqualTo(DocumentChunk.TYPE_PARENT);
        assertThat(indexedChunk.getValue().parentChunkId()).isNull();
    }

    @Test
    void syncChunkShouldWaitUntilDocumentEntersIndexingState() {
        DocumentChunk chunk = chunk();
        Document document = document();
        document.setIndexStatus("CHUNKING");
        when(documentChunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(10L)).thenReturn(document);

        boolean synced = syncService.syncChunk(1L);

        assertThat(synced).isFalse();
        verify(embeddingService, never()).embed(any());
        verify(elasticsearchChunkIndexer, never()).index(any());
    }

    @Test
    void syncMissingChunksShouldReplayAllMissingChunksForDocument() {
        DocumentChunk chunk = chunk();
        when(documentChunkMapper.selectMissingEsDocIdByDocumentId(10L)).thenReturn(java.util.List.of(chunk));
        when(documentChunkMapper.selectById(1L)).thenReturn(chunk);
        when(documentMapper.selectById(10L)).thenReturn(document());
        when(embeddingService.embed("chunk content")).thenReturn(new float[]{0.1f, 0.2f});
        when(elasticsearchChunkIndexer.index(any(IndexedChunk.class))).thenReturn("1");
        when(documentChunkMapper.countByDocumentId(10L)).thenReturn(1);
        when(documentChunkMapper.countMissingEsDocIdByDocumentId(10L)).thenReturn(0);

        syncService.syncMissingChunks(10L);

        verify(documentChunkMapper).updateEsDocIdIfMissing(1L, "1");
    }

    @Test
    void markDocumentIndexedShouldWaitForAllChunks() {
        Document document = document();
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(documentChunkMapper.countByDocumentId(10L)).thenReturn(2);
        when(documentChunkMapper.countMissingEsDocIdByDocumentId(10L)).thenReturn(1);

        syncService.markDocumentIndexedIfComplete(10L);

        verify(documentMapper, never()).updateById(any(Document.class));
    }

    private DocumentChunk chunk() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocumentId(10L);
        chunk.setKnowledgeBaseId(20L);
        chunk.setUserId(30L);
        chunk.setParentChunkId(1L);
        chunk.setChunkType(DocumentChunk.TYPE_CHILD);
        chunk.setChunkIndex(0);
        chunk.setContent("chunk content");
        chunk.setMetadataJson("{}");
        return chunk;
    }

    private Document document() {
        Document document = new Document();
        document.setId(10L);
        document.setTitle("demo");
        document.setParseStatus("PARSED");
        document.setIndexStatus("INDEXING");
        document.setErrorMessage("previous failure");
        return document;
    }
}
