package com.studyagent.ingest.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.ingest.sync.DocumentIndexProducer;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import com.studyagent.model.Document;
import com.studyagent.model.FileRecord;
import com.studyagent.model.UploadSession;
import com.studyagent.rag.web.KnowledgeBaseService;
import com.studyagent.mapper.UploadSessionMapper;
import com.studyagent.ingest.web.InitMultipartUploadRequest;
import com.studyagent.ingest.web.InitMultipartUploadResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class FileUploadServiceTest {

    @Mock
    private FileRecordMapper fileRecordMapper;
    @Mock
    private UploadSessionMapper uploadSessionMapper;
    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private ObjectStorageService objectStorageService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private DocumentIndexProducer documentIndexProducer;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private RLock lock;

    private FileUploadService fileUploadService;

    @BeforeEach
    void setUp() {
        fileUploadService = new FileUploadService(
                fileRecordMapper,
                uploadSessionMapper,
                documentMapper,
                objectStorageService,
                redissonClient,
                stringRedisTemplate,
                documentIndexProducer,
                knowledgeBaseService
        );
    }

    @Test
    void initMultipartShouldReuseActiveSessionForResume() {
        UploadSession session = uploadSession();
        when(redissonClient.getLock("lock:file:dedup:" + HASH)).thenReturn(lock);
        when(fileRecordMapper.selectOne(any())).thenReturn(null);
        when(uploadSessionMapper.selectActiveSession(1L, 1L, HASH)).thenReturn(session);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit("upload:bitmap:1", 0)).thenReturn(true);
        when(valueOperations.getBit("upload:bitmap:1", 1)).thenReturn(false);

        InitMultipartUploadResponse response = fileUploadService.initMultipart(1L, new InitMultipartUploadRequest(
                1L,
                "demo.pdf",
                "application/pdf",
                HASH.toUpperCase(),
                2L,
                1,
                2
        ));

        assertThat(response.uploadSessionId()).isEqualTo(1L);
        assertThat(response.duplicated()).isFalse();
        assertThat(response.uploadedChunks()).isEqualTo(1);
        assertThat(response.totalChunks()).isEqualTo(2);
        verify(uploadSessionMapper, never()).insert(any(UploadSession.class));
    }

    @Test
    void uploadSingleShouldDeduplicateBySha256BeforeWritingObject() {
        String contentHash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        FileRecord existing = new FileRecord();
        existing.setId(22L);
        existing.setStatus("STORED");
        when(redissonClient.getLock("lock:file:dedup:" + contentHash)).thenReturn(lock);
        when(fileRecordMapper.selectOne(any())).thenReturn(existing);

        var response = fileUploadService.uploadSingle(
                1L,
                1L,
                new MockMultipartFile("file", "demo.pdf", "application/pdf", "hello".getBytes(StandardCharsets.UTF_8))
        );

        assertThat(response.fileId()).isEqualTo(22L);
        assertThat(response.status()).isEqualTo("DUPLICATED");
        verify(objectStorageService, never()).putObject(any(), any(), anyLong(), any());
        verify(documentMapper).insert(any(Document.class));
        verify(documentIndexProducer).send(null, 1L);
    }

    @Test
    void uploadChunkShouldNotRecountAlreadyUploadedChunk() {
        UploadSession session = uploadSession();
        when(uploadSessionMapper.selectOne(any())).thenReturn(session);
        when(redissonClient.getLock("lock:upload:chunk:1:0")).thenReturn(lock);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit("upload:bitmap:1", 0)).thenReturn(true);

        fileUploadService.uploadChunk(1L, 1L, 0, new MockMultipartFile("chunk", "a".getBytes()));

        verify(objectStorageService, never()).putObject(any(), any(), anyLong(), any());
        verify(uploadSessionMapper, never()).updateById(any(UploadSession.class));
    }

    @Test
    void uploadChunkShouldMarkBitmapAndRefreshTtl() {
        UploadSession session = uploadSession();
        when(uploadSessionMapper.selectOne(any())).thenReturn(session);
        when(redissonClient.getLock("lock:upload:chunk:1:0")).thenReturn(lock);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit("upload:bitmap:1", 0)).thenReturn(false, true);
        when(valueOperations.getBit("upload:bitmap:1", 1)).thenReturn(false);

        fileUploadService.uploadChunk(1L, 1L, 0, new MockMultipartFile("chunk", "a".getBytes()));

        verify(objectStorageService).putObject(eq("multipart/1/0.part"), any(), eq(1L), eq("application/octet-stream"));
        verify(valueOperations).setBit("upload:bitmap:1", 0, true);
        verify(stringRedisTemplate).expire(eq("upload:bitmap:1"), any(Duration.class));
        verify(uploadSessionMapper).updateById(session);
    }

    @Test
    void completeMultipartShouldRejectMissingChunks() {
        UploadSession session = uploadSession();
        when(uploadSessionMapper.selectOne(any())).thenReturn(session);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit("upload:bitmap:1", 0)).thenReturn(true);
        when(valueOperations.getBit("upload:bitmap:1", 1)).thenReturn(false);

        assertThatThrownBy(() -> fileUploadService.completeMultipart(1L, 1L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少分片");

        verify(redissonClient, never()).getLock(eq("lock:file:dedup:" + HASH));
    }

    @Test
    void completeMultipartShouldRejectDifferentKnowledgeBase() {
        UploadSession session = uploadSession();
        when(uploadSessionMapper.selectOne(any())).thenReturn(session);

        assertThatThrownBy(() -> fileUploadService.completeMultipart(1L, 1L, 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("知识库");

        verify(redissonClient, never()).getLock(eq("lock:file:dedup:" + HASH));
    }

    private UploadSession uploadSession() {
        UploadSession session = new UploadSession();
        session.setId(1L);
        session.setUserId(1L);
        session.setKnowledgeBaseId(1L);
        session.setFileHash(HASH);
        session.setFilename("demo.pdf");
        session.setContentType("application/pdf");
        session.setChunkSize(1);
        session.setTotalChunks(2);
        session.setUploadedChunks(1);
        session.setFileSize(2L);
        session.setStatus("UPLOADING");
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        return session;
    }

    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
}
