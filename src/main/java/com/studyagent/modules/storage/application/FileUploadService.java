package com.studyagent.modules.storage.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.common.config.ObjectStorageProperties;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.mq.DocumentIndexProducer;
import com.studyagent.infrastructure.objectstorage.ObjectStorageService;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import com.studyagent.modules.storage.domain.FileRecord;
import com.studyagent.modules.storage.domain.UploadSession;
import com.studyagent.modules.storage.infrastructure.FileRecordMapper;
import com.studyagent.modules.storage.infrastructure.UploadSessionMapper;
import com.studyagent.modules.storage.interfaces.InitMultipartUploadRequest;
import com.studyagent.modules.storage.interfaces.InitMultipartUploadResponse;
import com.studyagent.modules.storage.interfaces.UploadResultResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class FileUploadService {

    private static final Long DEFAULT_USER_ID = KnowledgeBaseService.DEFAULT_USER_ID;

    private final FileRecordMapper fileRecordMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final DocumentMapper documentMapper;
    private final ObjectStorageService objectStorageService;
    private final ObjectStorageProperties storageProperties;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final DocumentIndexProducer documentIndexProducer;

    @Transactional
    public UploadResultResponse uploadSingle(Long knowledgeBaseId, MultipartFile file) {
        String md5 = calculateMd5(file);
        RLock lock = redissonClient.getLock("lock:file:dedup:" + md5);
        lock.lock();
        try {
            FileRecord existing = findByMd5(md5);
            if (existing != null) {
                Document document = createDocument(DEFAULT_USER_ID, knowledgeBaseId, existing, file.getOriginalFilename());
                documentIndexProducer.send(document.getId());
                return new UploadResultResponse(existing.getId(), document.getId(), "DUPLICATED");
            }

            String objectKey = "files/" + md5 + "/" + safeFilename(file.getOriginalFilename());
            objectStorageService.putObject(objectKey, fileInputStream(file), file.getSize(), contentType(file.getContentType()));
            FileRecord fileRecord = createFileRecord(md5, objectKey, file.getOriginalFilename(), file.getContentType(), file.getSize());
            Document document = createDocument(DEFAULT_USER_ID, knowledgeBaseId, fileRecord, file.getOriginalFilename());
            documentIndexProducer.send(document.getId());
            return new UploadResultResponse(fileRecord.getId(), document.getId(), "UPLOADED");
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public InitMultipartUploadResponse initMultipart(InitMultipartUploadRequest request) {
        RLock lock = redissonClient.getLock("lock:file:dedup:" + request.md5());
        lock.lock();
        try {
            FileRecord existing = findByMd5(request.md5());
            if (existing != null) {
                Document document = createDocument(DEFAULT_USER_ID, request.knowledgeBaseId(), existing, request.filename());
                documentIndexProducer.send(document.getId());
                return new InitMultipartUploadResponse(null, true, existing.getId(), document.getId(), "DUPLICATED");
            }

            LocalDateTime now = LocalDateTime.now();
            UploadSession session = new UploadSession();
            session.setUserId(DEFAULT_USER_ID);
            session.setFileMd5(request.md5());
            session.setFilename(request.filename());
            session.setContentType(request.contentType());
            session.setChunkSize(request.chunkSize());
            session.setTotalChunks(request.totalChunks());
            session.setUploadedChunks(0);
            session.setFileSize(request.fileSize());
            session.setStatus("UPLOADING");
            session.setExpiresAt(now.plusDays(1));
            session.setCreatedAt(now);
            session.setUpdatedAt(now);
            uploadSessionMapper.insert(session);
            return new InitMultipartUploadResponse(session.getId(), false, null, null, "UPLOADING");
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void uploadChunk(Long uploadSessionId, int chunkIndex, MultipartFile chunk) {
        UploadSession session = requiredUploadSession(uploadSessionId);
        if (!"UPLOADING".equals(session.getStatus())) {
            throw new BusinessException("上传会话状态不是 UPLOADING");
        }
        if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
            throw new BusinessException("分片序号超出范围");
        }

        String chunkKey = chunkObjectKey(session, chunkIndex);
        objectStorageService.putObject(chunkKey, fileInputStream(chunk), chunk.getSize(), "application/octet-stream");

        String bitmapKey = uploadBitmapKey(uploadSessionId);
        Boolean alreadyUploaded = stringRedisTemplate.opsForValue().getBit(bitmapKey, chunkIndex);
        stringRedisTemplate.opsForValue().setBit(bitmapKey, chunkIndex, true);
        if (!Boolean.TRUE.equals(alreadyUploaded)) {
            session.setUploadedChunks(session.getUploadedChunks() + 1);
            session.setUpdatedAt(LocalDateTime.now());
            uploadSessionMapper.updateById(session);
        }
    }

    @Transactional
    public UploadResultResponse completeMultipart(Long uploadSessionId, Long knowledgeBaseId) {
        UploadSession session = requiredUploadSession(uploadSessionId);
        if (!"UPLOADING".equals(session.getStatus())) {
            throw new BusinessException("上传会话状态不是 UPLOADING");
        }
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Boolean uploaded = stringRedisTemplate.opsForValue().getBit(uploadBitmapKey(uploadSessionId), i);
            if (!Boolean.TRUE.equals(uploaded)) {
                throw new BusinessException("分片未上传完整，缺少分片: " + i);
            }
        }

        RLock lock = redissonClient.getLock("lock:file:dedup:" + session.getFileMd5());
        lock.lock();
        try {
            FileRecord existing = findByMd5(session.getFileMd5());
            if (existing != null) {
                session.setStatus("COMPLETED");
                session.setUpdatedAt(LocalDateTime.now());
                uploadSessionMapper.updateById(session);
                Document document = createDocument(DEFAULT_USER_ID, knowledgeBaseId, existing, session.getFilename());
                documentIndexProducer.send(document.getId());
                return new UploadResultResponse(existing.getId(), document.getId(), "DUPLICATED");
            }

            byte[] merged = mergeChunks(session);
            String actualMd5 = calculateMd5(merged);
            if (!session.getFileMd5().equalsIgnoreCase(actualMd5)) {
                throw new BusinessException("合并文件 MD5 与初始化 MD5 不一致");
            }

            String objectKey = "files/" + session.getFileMd5() + "/" + safeFilename(session.getFilename());
            objectStorageService.putObject(objectKey, new ByteArrayInputStream(merged), merged.length, contentType(session.getContentType()));
            FileRecord fileRecord = createFileRecord(session.getFileMd5(), objectKey, session.getFilename(),
                    session.getContentType(), session.getFileSize());

            session.setStatus("COMPLETED");
            session.setUpdatedAt(LocalDateTime.now());
            uploadSessionMapper.updateById(session);

            Document document = createDocument(DEFAULT_USER_ID, knowledgeBaseId, fileRecord, session.getFilename());
            documentIndexProducer.send(document.getId());
            return new UploadResultResponse(fileRecord.getId(), document.getId(), "UPLOADED");
        } finally {
            lock.unlock();
        }
    }

    private byte[] mergeChunks(UploadSession session) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.toIntExact(session.getFileSize()));
        for (int i = 0; i < session.getTotalChunks(); i++) {
            try (InputStream inputStream = objectStorageService.getObject(chunkObjectKey(session, i))) {
                inputStream.transferTo(outputStream);
            } catch (Exception ex) {
                throw new BusinessException("读取分片失败: " + i);
            }
        }
        return outputStream.toByteArray();
    }

    private UploadSession requiredUploadSession(Long uploadSessionId) {
        UploadSession session = uploadSessionMapper.selectById(uploadSessionId);
        if (session == null) {
            throw new BusinessException("上传会话不存在");
        }
        return session;
    }

    private FileRecord findByMd5(String md5) {
        return fileRecordMapper.selectOne(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getMd5, md5)
                .last("LIMIT 1"));
    }

    private FileRecord createFileRecord(String md5, String objectKey, String filename, String contentType, long size) {
        LocalDateTime now = LocalDateTime.now();
        FileRecord fileRecord = new FileRecord();
        fileRecord.setUserId(DEFAULT_USER_ID);
        fileRecord.setMd5(md5);
        fileRecord.setBucket(storageProperties.bucket());
        fileRecord.setObjectKey(objectKey);
        fileRecord.setFilename(safeFilename(filename));
        fileRecord.setContentType(contentType(contentType));
        fileRecord.setSize(size);
        fileRecord.setStorageProvider("RUSTFS_S3");
        fileRecord.setStatus("ACTIVE");
        fileRecord.setCreatedAt(now);
        fileRecord.setUpdatedAt(now);
        fileRecordMapper.insert(fileRecord);
        return fileRecord;
    }

    private Document createDocument(Long userId, Long knowledgeBaseId, FileRecord fileRecord, String title) {
        LocalDateTime now = LocalDateTime.now();
        Document document = new Document();
        document.setUserId(userId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileId(fileRecord.getId());
        document.setTitle(safeFilename(title));
        document.setSourceType("UPLOAD");
        document.setParseStatus("UPLOADED");
        document.setIndexStatus("PENDING");
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentMapper.insert(document);
        return document;
    }

    private String calculateMd5(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new BusinessException("计算文件 MD5 失败");
        }
    }

    private String calculateMd5(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception ex) {
            throw new BusinessException("计算合并文件 MD5 失败");
        }
    }

    private InputStream fileInputStream(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (Exception ex) {
            throw new BusinessException("读取上传文件失败");
        }
    }

    private String chunkObjectKey(UploadSession session, int chunkIndex) {
        return "multipart/" + session.getId() + "/" + chunkIndex + ".part";
    }

    private String uploadBitmapKey(Long uploadSessionId) {
        return "upload:bitmap:" + uploadSessionId;
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        return filename.replace("\\", "_").replace("/", "_");
    }

    private String contentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType;
    }
}
