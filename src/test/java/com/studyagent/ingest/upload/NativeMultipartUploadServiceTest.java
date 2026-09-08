package com.studyagent.ingest.upload;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.UploadProperties;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.ingest.web.InitMultipartUploadRequest;
import com.studyagent.ingest.web.UploadResultResponse;
import com.studyagent.mapper.UploadPartMapper;
import com.studyagent.mapper.UploadSessionMapper;
import com.studyagent.model.UploadPart;
import com.studyagent.model.UploadSession;
import com.studyagent.rag.web.KnowledgeBaseService;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class NativeMultipartUploadServiceTest {
    @Mock UploadSessionMapper sessions;
    @Mock UploadPartMapper parts;
    @Mock UploadPublicationService publication;
    @Mock ObjectStorageService storage;
    @Mock RedissonClient locks;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock KnowledgeBaseService knowledgeBases;
    @Mock RReadWriteLock rw;
    @Mock RLock write;
    @Mock RLock read;
    @Mock RLock keyed;
    NativeMultipartUploadService service;
    UploadSession session;
    static final String HASH = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @BeforeEach
    void setup() {
        service = new NativeMultipartUploadService(sessions, parts, publication, storage, locks, redis,
                knowledgeBases, new UploadProperties(Duration.ofDays(1), 1073741824L, 67108864));
        session = new UploadSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setKnowledgeBaseId(2L);
        session.setFilename("test.txt");
        session.setContentType("text/plain");
        session.setFileHash(HASH);
        session.setFileSize(5L);
        session.setChunkSize(5);
        session.setTotalChunks(1);
        session.setStorageKey("unique-key");
        session.setStorageUploadId("native-id");
        session.setStatus("UPLOADING");
        session.setExpiresAt(LocalDateTime.now().plusDays(1));
    }

    UploadPart part() {
        UploadPart part = new UploadPart();
        part.setChunkIndex(0);
        part.setEtag("etag-1");
        part.setByteSize(5L);
        return part;
    }

    void completionSetup() {
        when(locks.getReadWriteLock(anyString())).thenReturn(rw);
        when(rw.writeLock()).thenReturn(write);
        when(sessions.selectOne(any())).thenReturn(session);
    }

    void publicationSetup() {
        when(locks.getLock(anyString())).thenReturn(keyed);
        when(publication.publish(1L, 2L, HASH, "unique-key", "test.txt", 5L, "text/plain", 10L))
                .thenReturn(new UploadResultResponse(30L, 40L, "UPLOADED"));
    }

    @Test
    void resumesLostCompleteResponseFromExistingObjectAndStillChecksHash() {
        completionSetup();
        publicationSetup();
        session.setStatus("MERGING");
        when(parts.listParts(10L)).thenReturn(List.of(part()));
        when(storage.objectSize("unique-key")).thenReturn(5L);
        when(storage.getObject("unique-key")).thenReturn(new ByteArrayInputStream("hello".getBytes()));
        assertThat(service.complete(1L, 10L, 2L).documentId()).isEqualTo(40L);
        verify(storage, never()).completeMultipartUpload(any(), any(), any());
        verify(sessions).setPhase(eq(10L), eq("VERIFIED"), any());
        verify(keyed).unlock();
    }

    @Test
    void verifiedRecoverySkipsStorageWork() {
        completionSetup();
        publicationSetup();
        session.setStatus("VERIFIED");
        when(parts.listParts(10L)).thenReturn(List.of(part()));
        service.complete(1L, 10L, 2L);
        verifyNoInteractions(storage);
    }

    @Test
    void hashMismatchPersistsFailureWithoutPublishing() {
        completionSetup();
        session.setStatus("VERIFYING");
        when(parts.listParts(10L)).thenReturn(List.of(part()));
        when(storage.getObject("unique-key")).thenReturn(new ByteArrayInputStream("wrong".getBytes()));
        assertThatThrownBy(() -> service.complete(1L, 10L, 2L)).isInstanceOf(BusinessException.class).hasMessageContaining("SHA-256");
        verify(sessions).setPhase(eq(10L), eq("HASH_FAILED"), any());
        verify(sessions).setError(eq(10L), contains("HASH_FAILED"), any());
        verifyNoInteractions(publication);
    }

    @Test
    void repeatedCompleteReturnsSavedIdsWithoutSideEffects() {
        completionSetup();
        session.setStatus("COMPLETED");
        session.setCompletedFileId(30L);
        session.setCompletedDocumentId(40L);
        assertThat(service.complete(1L, 10L, 2L).documentId()).isEqualTo(40L);
        verifyNoInteractions(storage, parts, publication);
    }

    @Test
    void missingPartsRejectBeforeMerge() {
        completionSetup();
        when(parts.listParts(10L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.complete(1L, 10L, 2L)).hasMessageContaining("缺少分片");
        verifyNoInteractions(storage, publication);
    }

    @Test
    void otherKnowledgeBaseCannotCompleteSession() {
        completionSetup();
        assertThatThrownBy(() -> service.complete(1L, 10L, 3L)).hasMessageContaining("知识库");
        verifyNoInteractions(storage, parts, publication);
    }

    @Test
    void bitmapEvictionDoesNotLoseDurableProgress() {
        when(sessions.selectOne(any())).thenReturn(session);
        when(parts.listParts(10L)).thenReturn(List.of(part()));
        when(redis.opsForValue()).thenReturn(values);
        var status = service.status(1L, 10L);
        assertThat(status.uploadedChunkIndexes()).containsExactly(0);
        assertThat(status.missingChunkIndexes()).isEmpty();
        verify(values).setBit("upload:bitmap:10", 0, true);
    }

    @Test
    void duplicatePartUsesSavedEtagWithoutReuploading() {
        when(locks.getReadWriteLock(anyString())).thenReturn(rw);
        when(rw.readLock()).thenReturn(read);
        when(locks.getLock(anyString())).thenReturn(keyed);
        when(sessions.selectOne(any())).thenReturn(session);
        when(parts.selectOne(any())).thenReturn(part());
        when(redis.opsForValue()).thenReturn(values);
        service.uploadPart(1L, 10L, 0, new MockMultipartFile("chunk", "hello".getBytes()));
        verifyNoInteractions(storage);
        verify(parts, never()).insert(any(UploadPart.class));
        verify(read).unlock();
    }

    @Test
    void rejectsTooSmallNonFinalPartAtInit() {
        assertThatThrownBy(() -> service.init(1L, new InitMultipartUploadRequest(2L, "test.txt", "text/plain", HASH, 10L, 5, 2)))
                .hasMessageContaining("5 MiB");
        verifyNoInteractions(storage, publication, sessions);
    }

    @Test
    void cancelRemovesMergedUnpublishedObject() {
        completionSetup();
        session.setStatus("HASH_FAILED");
        when(storage.objectSize("unique-key")).thenReturn(5L);
        service.cancel(1L, 10L);
        verify(storage).deleteObject("unique-key");
        verify(storage, never()).abortMultipartUpload(any(), any());
        verify(sessions).setPhase(eq(10L), eq("CANCELLED"), any());
    }
}
