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
import com.studyagent.modules.storage.interfaces.FileDedupCheckResponse;
import com.studyagent.modules.storage.interfaces.InitMultipartUploadRequest;
import com.studyagent.modules.storage.interfaces.InitMultipartUploadResponse;
import com.studyagent.modules.storage.interfaces.MultipartUploadStatusResponse;
import com.studyagent.modules.storage.interfaces.UploadResultResponse;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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

    public FileDedupCheckResponse checkDuplicate(String md5, String sha256) {
        String normalizedMd5 = normalizeMd5(md5);
        FileRecord existing = findDuplicate(normalizedMd5, normalizeSha256(sha256));
        if (existing == null) {
            return new FileDedupCheckResponse(false, null, "NOT_FOUND");
        }
        return new FileDedupCheckResponse(true, existing.getId(), existing.getStatus());
    }

    @Transactional
    public UploadResultResponse uploadSingle(Long knowledgeBaseId, MultipartFile file) {
        String md5 = calculateMd5(file);
        String sha256 = calculateSha256(file);
        RLock lock = redissonClient.getLock("lock:file:dedup:" + md5);
        lock.lock();
        try {
            FileRecord existing = findDuplicate(md5, sha256);
            if (existing != null) {
                Document document = createDocument(DEFAULT_USER_ID, knowledgeBaseId, existing, file.getOriginalFilename());
                documentIndexProducer.send(document.getId());
                return new UploadResultResponse(existing.getId(), document.getId(), "DUPLICATED");
            }

            String objectKey = "files/" + md5 + "/" + safeFilename(file.getOriginalFilename());
            objectStorageService.putObject(objectKey, fileInputStream(file), file.getSize(), contentType(file.getContentType()));
            FileRecord fileRecord = createFileRecord(md5, sha256, objectKey, file.getOriginalFilename(), file.getContentType(), file.getSize());
            Document document = createDocument(DEFAULT_USER_ID, knowledgeBaseId, fileRecord, file.getOriginalFilename());
            documentIndexProducer.send(document.getId());
            return new UploadResultResponse(fileRecord.getId(), document.getId(), "UPLOADED");
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public InitMultipartUploadResponse initMultipart(InitMultipartUploadRequest request) {
        String md5 = normalizeMd5(request.md5());
        String sha256 = normalizeSha256(request.sha256());
        validateInitRequest(request);
        RLock lock = redissonClient.getLock("lock:file:dedup:" + md5);
        lock.lock();
        try {
            FileRecord existing = findDuplicate(md5, sha256);
            if (existing != null) {
                Document document = createDocument(DEFAULT_USER_ID, request.knowledgeBaseId(), existing, request.filename());
                documentIndexProducer.send(document.getId());
                return new InitMultipartUploadResponse(null, true, existing.getId(), document.getId(), "DUPLICATED", 0, request.totalChunks());
            }

            UploadSession activeSession = uploadSessionMapper.selectActiveSession(
                    DEFAULT_USER_ID,
                    request.knowledgeBaseId(),
                    md5
            );
            if (activeSession != null) {
                refreshUploadedChunks(activeSession);
                return new InitMultipartUploadResponse(
                        activeSession.getId(),
                        false,
                        null,
                        null,
                        activeSession.getStatus(),
                        activeSession.getUploadedChunks(),
                        activeSession.getTotalChunks()
                );
            }

            LocalDateTime now = LocalDateTime.now();
            UploadSession session = new UploadSession();
            session.setUserId(DEFAULT_USER_ID);
            session.setKnowledgeBaseId(request.knowledgeBaseId());
            session.setFileMd5(md5);
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
            return new InitMultipartUploadResponse(session.getId(), false, null, null, "UPLOADING", 0, session.getTotalChunks());
        } finally {
            lock.unlock();
        }
    }

    @Transactional
    public void uploadChunk(Long uploadSessionId, int chunkIndex, MultipartFile chunk) {
        UploadSession session = requiredUploadSession(uploadSessionId);
        validateUploadableSession(session);
        validateChunk(session, chunkIndex, chunk);

        RLock lock = redissonClient.getLock("lock:upload:chunk:" + uploadSessionId + ":" + chunkIndex);
        lock.lock();
        try {
            String bitmapKey = uploadBitmapKey(uploadSessionId);
            Boolean alreadyUploaded = stringRedisTemplate.opsForValue().getBit(bitmapKey, chunkIndex);
            if (Boolean.TRUE.equals(alreadyUploaded)) {
                return;
            }

            String chunkKey = chunkObjectKey(session, chunkIndex);
            objectStorageService.putObject(chunkKey, fileInputStream(chunk), chunk.getSize(), "application/octet-stream");
            stringRedisTemplate.opsForValue().setBit(bitmapKey, chunkIndex, true);
            refreshBitmapTtl(session);
            session.setUploadedChunks(countUploadedChunks(session));
            session.setUpdatedAt(LocalDateTime.now());
            uploadSessionMapper.updateById(session);
        } finally {
            lock.unlock();
        }
    }

    public MultipartUploadStatusResponse multipartStatus(Long uploadSessionId) {
        UploadSession session = requiredUploadSession(uploadSessionId);
        refreshUploadedChunks(session);
        return toMultipartStatus(session);
    }

    @Transactional
    public UploadResultResponse completeMultipart(Long uploadSessionId, Long knowledgeBaseId) {
        UploadSession session = requiredUploadSession(uploadSessionId);
        validateUploadableSession(session);
        validateCompleteKnowledgeBase(session, knowledgeBaseId);
        List<Integer> missingChunks = missingChunks(session);
        if (!missingChunks.isEmpty()) {
            throw new BusinessException("分片未上传完整，缺少分片: " + missingChunks);
        }

        RLock lock = redissonClient.getLock("lock:file:dedup:" + session.getFileMd5());
        lock.lock();
        try {
            FileRecord existing = findByMd5(session.getFileMd5());
            if (existing != null) {
                session.setStatus("COMPLETED");
                session.setCompletedFileId(existing.getId());
                session.setUpdatedAt(LocalDateTime.now());
                Document document = createDocument(DEFAULT_USER_ID, knowledgeBaseId, existing, session.getFilename());
                session.setCompletedDocumentId(document.getId());
                uploadSessionMapper.updateById(session);
                documentIndexProducer.send(document.getId());
                return new UploadResultResponse(existing.getId(), document.getId(), "DUPLICATED");
            }

            FileHashes hashes = calculateMergedHashes(session);
            String actualMd5 = hashes.md5();
            if (!session.getFileMd5().equalsIgnoreCase(actualMd5)) {
                throw new BusinessException("合并文件 MD5 与初始化 MD5 不一致");
            }
            String actualSha256 = hashes.sha256();
            FileRecord duplicateAfterHash = findDuplicate(actualMd5, actualSha256);
            if (duplicateAfterHash != null) {
                session.setStatus("COMPLETED");
                session.setCompletedFileId(duplicateAfterHash.getId());
                session.setUpdatedAt(LocalDateTime.now());
                Document document = createDocument(DEFAULT_USER_ID, knowledgeBaseId, duplicateAfterHash, session.getFilename());
                session.setCompletedDocumentId(document.getId());
                uploadSessionMapper.updateById(session);
                documentIndexProducer.send(document.getId());
                return new UploadResultResponse(duplicateAfterHash.getId(), document.getId(), "DUPLICATED");
            }

            String objectKey = "files/" + session.getFileMd5() + "/" + safeFilename(session.getFilename());
            putMergedObject(session, objectKey);
            FileRecord fileRecord = createFileRecord(session.getFileMd5(), actualSha256, objectKey, session.getFilename(),
                    session.getContentType(), session.getFileSize());

            session.setStatus("COMPLETED");
            session.setCompletedFileId(fileRecord.getId());
            session.setUpdatedAt(LocalDateTime.now());

            Document document = createDocument(DEFAULT_USER_ID, knowledgeBaseId, fileRecord, session.getFilename());
            session.setCompletedDocumentId(document.getId());
            uploadSessionMapper.updateById(session);
            documentIndexProducer.send(document.getId());
            return new UploadResultResponse(fileRecord.getId(), document.getId(), "UPLOADED");
        } finally {
            lock.unlock();
        }
    }

    private FileHashes calculateMergedHashes(UploadSession session) {
        MessageDigest md5Digest = messageDigest("MD5", "创建 MD5 摘要失败");
        MessageDigest sha256Digest = messageDigest("SHA-256", "创建 SHA256 摘要失败");
        byte[] buffer = new byte[8192];
        for (int i = 0; i < session.getTotalChunks(); i++) {
            try (InputStream inputStream = objectStorageService.getObject(chunkObjectKey(session, i))) {
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    md5Digest.update(buffer, 0, read);
                    sha256Digest.update(buffer, 0, read);
                }
            } catch (IOException | RuntimeException ex) {
                throw new BusinessException("读取分片失败: " + i);
            }
        }
        return new FileHashes(
                HexFormat.of().formatHex(md5Digest.digest()),
                HexFormat.of().formatHex(sha256Digest.digest())
        );
    }

    private void putMergedObject(UploadSession session, String objectKey) {
        try (InputStream inputStream = new ChunkSequenceInputStream(session)) {
            objectStorageService.putObject(objectKey, inputStream, session.getFileSize(), contentType(session.getContentType()));
        } catch (IOException ex) {
            throw new BusinessException("合并上传文件失败");
        }
    }

    private UploadSession requiredUploadSession(Long uploadSessionId) {
        UploadSession session = uploadSessionMapper.selectById(uploadSessionId);
        if (session == null) {
            throw new BusinessException("上传会话不存在");
        }
        return session;
    }

    private FileRecord findDuplicate(String md5, String sha256) {
        FileRecord existing = findByMd5(md5);
        if (existing != null) {
            return existing;
        }
        if (sha256 == null || sha256.isBlank()) {
            return null;
        }
        return fileRecordMapper.selectOne(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getSha256, sha256)
                .last("LIMIT 1"));
    }

    private FileRecord findByMd5(String md5) {
        return fileRecordMapper.selectOne(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getMd5, md5)
                .last("LIMIT 1"));
    }

    private FileRecord createFileRecord(String md5, String sha256, String objectKey, String filename, String contentType, long size) {
        LocalDateTime now = LocalDateTime.now();
        FileRecord fileRecord = new FileRecord();
        fileRecord.setUserId(DEFAULT_USER_ID);
        fileRecord.setMd5(md5);
        fileRecord.setSha256(sha256);
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

    private String calculateSha256(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new BusinessException("计算文件 SHA256 失败");
        }
    }

    private String calculateMd5(byte[] bytes) {
        MessageDigest digest = messageDigest("MD5", "计算合并文件 MD5 失败");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private String calculateSha256(byte[] bytes) {
        MessageDigest digest = messageDigest("SHA-256", "计算合并文件 SHA256 失败");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private MessageDigest messageDigest(String algorithm, String errorMessage) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (Exception ex) {
            throw new BusinessException(errorMessage);
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

    private void validateInitRequest(InitMultipartUploadRequest request) {
        long expectedChunks = (request.fileSize() + request.chunkSize() - 1) / request.chunkSize();
        if (expectedChunks != request.totalChunks()) {
            throw new BusinessException("totalChunks 与 fileSize/chunkSize 不匹配");
        }
    }

    private void validateUploadableSession(UploadSession session) {
        if (!"UPLOADING".equals(session.getStatus())) {
            throw new BusinessException("上传会话状态不是 UPLOADING");
        }
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus("EXPIRED");
            session.setUpdatedAt(LocalDateTime.now());
            uploadSessionMapper.updateById(session);
            throw new BusinessException("上传会话已过期");
        }
    }

    private void validateCompleteKnowledgeBase(UploadSession session, Long knowledgeBaseId) {
        if (session.getKnowledgeBaseId() != null && !session.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new BusinessException("完成上传的知识库与初始化上传的知识库不一致");
        }
    }

    private void validateChunk(UploadSession session, int chunkIndex, MultipartFile chunk) {
        if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
            throw new BusinessException("分片序号超出范围");
        }
        long expectedMaxSize = session.getChunkSize();
        if (chunkIndex < session.getTotalChunks() - 1 && chunk.getSize() != expectedMaxSize) {
            throw new BusinessException("非最后分片大小必须等于 chunkSize");
        }
        if (chunkIndex == session.getTotalChunks() - 1) {
            long expectedLastSize = session.getFileSize() - (long) session.getChunkSize() * (session.getTotalChunks() - 1);
            if (chunk.getSize() != expectedLastSize) {
                throw new BusinessException("最后分片大小不正确");
            }
        }
    }

    private void refreshUploadedChunks(UploadSession session) {
        int uploadedChunks = countUploadedChunks(session);
        if (session.getUploadedChunks() == null || uploadedChunks != session.getUploadedChunks()) {
            session.setUploadedChunks(uploadedChunks);
            session.setUpdatedAt(LocalDateTime.now());
            uploadSessionMapper.updateById(session);
        }
    }

    private void refreshBitmapTtl(UploadSession session) {
        Duration ttl = Duration.between(LocalDateTime.now(), session.getExpiresAt());
        if (!ttl.isNegative() && !ttl.isZero()) {
            stringRedisTemplate.expire(uploadBitmapKey(session.getId()), ttl);
        }
    }

    private int countUploadedChunks(UploadSession session) {
        return uploadedChunks(session).size();
    }

    private List<Integer> uploadedChunks(UploadSession session) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Boolean uploaded = stringRedisTemplate.opsForValue().getBit(uploadBitmapKey(session.getId()), i);
            if (Boolean.TRUE.equals(uploaded)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private List<Integer> missingChunks(UploadSession session) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < session.getTotalChunks(); i++) {
            Boolean uploaded = stringRedisTemplate.opsForValue().getBit(uploadBitmapKey(session.getId()), i);
            if (!Boolean.TRUE.equals(uploaded)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private MultipartUploadStatusResponse toMultipartStatus(UploadSession session) {
        List<Integer> uploaded = uploadedChunks(session);
        List<Integer> missing = missingChunks(session);
        return new MultipartUploadStatusResponse(
                session.getId(),
                session.getKnowledgeBaseId(),
                session.getFilename(),
                session.getFileMd5(),
                session.getFileSize(),
                session.getChunkSize(),
                session.getTotalChunks(),
                uploaded.size(),
                uploaded,
                missing,
                session.getStatus(),
                session.getCompletedFileId(),
                session.getCompletedDocumentId(),
                session.getExpiresAt()
        );
    }

    private String normalizeMd5(String md5) {
        if (md5 == null || md5.isBlank()) {
            throw new BusinessException("文件 MD5 不能为空");
        }
        String normalized = md5.toLowerCase(Locale.ROOT);
        if (normalized.length() != 32 || !isHex(normalized)) {
            throw new BusinessException("文件 MD5 格式不正确");
        }
        return normalized;
    }

    private String normalizeSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return null;
        }
        String normalized = sha256.toLowerCase(Locale.ROOT);
        if (normalized.length() != 64 || !isHex(normalized)) {
            throw new BusinessException("文件 SHA256 格式不正确");
        }
        return normalized;
    }

    private boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
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

    private record FileHashes(String md5, String sha256) {
    }

    private final class ChunkSequenceInputStream extends InputStream {
        private final UploadSession session;
        private int nextChunkIndex;
        private InputStream current;

        private ChunkSequenceInputStream(UploadSession session) {
            this.session = session;
        }

        @Override
        public int read() throws IOException {
            byte[] singleByte = new byte[1];
            int read = read(singleByte, 0, 1);
            if (read == -1) {
                return -1;
            }
            return singleByte[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            while (true) {
                if (current == null) {
                    if (nextChunkIndex >= session.getTotalChunks()) {
                        return -1;
                    }
                    current = openChunk(nextChunkIndex);
                    nextChunkIndex++;
                }
                int read = current.read(buffer, offset, length);
                if (read != -1) {
                    return read;
                }
                current.close();
                current = null;
            }
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
        }

        private InputStream openChunk(int chunkIndex) throws IOException {
            try {
                return objectStorageService.getObject(chunkObjectKey(session, chunkIndex));
            } catch (RuntimeException ex) {
                throw new IOException("读取分片失败: " + chunkIndex, ex);
            }
        }
    }
}
