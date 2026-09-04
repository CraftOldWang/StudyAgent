package com.studyagent.ingest.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.ingest.sync.DocumentIndexProducer;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.model.UploadSession;
import com.studyagent.mapper.UploadSessionMapper;
import com.studyagent.ingest.web.FileDedupCheckResponse;
import com.studyagent.ingest.web.InitMultipartUploadRequest;
import com.studyagent.ingest.web.InitMultipartUploadResponse;
import com.studyagent.ingest.web.MultipartUploadStatusResponse;
import com.studyagent.ingest.web.UploadResultResponse;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import com.studyagent.model.Document;
import com.studyagent.model.FileRecord;
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

/**
 * 文件上传用例服务，统一编排秒传、普通上传、分片上传和文档入库。
 *
 * <p>本类是 storage 模块的事务边界：对象存储只保存文件内容，MySQL 保存文件和上传会话状态，
 * Redis Bitmap 只承担分片上传过程中的短期状态记录。</p>
 */
@Service
@RequiredArgsConstructor
public class FileUploadService {

    private static final Long DEFAULT_USER_ID = 1L;

    private final FileRecordMapper fileRecordMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final DocumentMapper documentMapper;
    private final ObjectStorageService objectStorageService;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final DocumentIndexProducer documentIndexProducer;

    /**
     * 根据客户端计算出的哈希检查文件是否已经入库，用于前端上传前的秒传判断。
     */
    public FileDedupCheckResponse checkDuplicate(Long knowledgeBaseId, String sha256) {
        String normalizedSha256 = normalizeSha256(sha256);
        FileRecord existing = findDuplicate(DEFAULT_USER_ID, knowledgeBaseId, normalizedSha256);
        if (existing == null) {
            return new FileDedupCheckResponse(false, null, "NOT_FOUND");
        }
        return new FileDedupCheckResponse(true, existing.getId(), existing.getStatus());
    }

    /**
     * 处理小文件直传：先计算哈希并进入去重锁，再决定秒传或写入对象存储。
     */
    @Transactional
    public UploadResultResponse uploadSingle(Long knowledgeBaseId, MultipartFile file) {
        String fileHash = calculateSha256(file);
        RLock lock = redissonClient.getLock("lock:file:dedup:" + fileHash);
        lock.lock();
        try {
            // 哈希相同的文件只复用文件实体，但仍然为当前知识库创建新的文档记录。
            FileRecord existing = findDuplicate(DEFAULT_USER_ID, knowledgeBaseId, fileHash);
            if (existing != null) {
                Document document = createDocument(
                        DEFAULT_USER_ID,
                        knowledgeBaseId,
                        existing,
                        file.getOriginalFilename(),
                        file.getContentType());
                documentIndexProducer.send(document.getId());
                return new UploadResultResponse(existing.getId(), document.getId(), "DUPLICATED");
            }

            // 对象 key 使用哈希分区，避免同名文件覆盖，同时保留原始文件名便于排查。
            String objectKey = "files/" + fileHash + "/" + safeFilename(file.getOriginalFilename());
            objectStorageService.putObject(objectKey, fileInputStream(file), file.getSize(), contentType(file.getContentType()));
            FileRecord fileRecord = createFileRecord(
                    DEFAULT_USER_ID,
                    knowledgeBaseId,
                    fileHash,
                    objectKey,
                    file.getOriginalFilename(),
                    file.getSize());
            Document document = createDocument(
                    DEFAULT_USER_ID,
                    knowledgeBaseId,
                    fileRecord,
                    file.getOriginalFilename(),
                    file.getContentType());
            documentIndexProducer.send(document.getId());
            return new UploadResultResponse(fileRecord.getId(), document.getId(), "UPLOADED");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 初始化分片上传会话，并返回当前文件是否可以秒传或是否存在未完成会话。
     */
    @Transactional
    public InitMultipartUploadResponse initMultipart(InitMultipartUploadRequest request) {
        String fileHash = normalizeSha256(request.sha256());
        validateInitRequest(request);
        RLock lock = redissonClient.getLock("lock:file:dedup:" + fileHash);
        lock.lock();
        try {
            // 初始化阶段就做去重，客户端可以跳过后续所有分片上传。
            FileRecord existing = findDuplicate(DEFAULT_USER_ID, request.knowledgeBaseId(), fileHash);
            if (existing != null) {
                Document document = createDocument(
                        DEFAULT_USER_ID,
                        request.knowledgeBaseId(),
                        existing,
                        request.filename(),
                        request.contentType());
                documentIndexProducer.send(document.getId());
                return new InitMultipartUploadResponse(null, true, existing.getId(), document.getId(), "DUPLICATED", 0, request.totalChunks());
            }

            UploadSession activeSession = uploadSessionMapper.selectActiveSession(
                    DEFAULT_USER_ID,
                    request.knowledgeBaseId(),
                    fileHash
            );
            if (activeSession != null) {
                // Redis Bitmap 是短期状态，返回前同步一次 MySQL 中的 uploadedChunks。
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

            // 新会话默认保留一天，过期后需要客户端重新初始化上传。
            LocalDateTime now = LocalDateTime.now();
            UploadSession session = new UploadSession();
            session.setUserId(DEFAULT_USER_ID);
            session.setKnowledgeBaseId(request.knowledgeBaseId());
            session.setFileHash(fileHash);
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

    /**
     * 上传单个分片。相同分片重复上传时直接幂等返回。
     */
    @Transactional
    public void uploadChunk(Long uploadSessionId, int chunkIndex, MultipartFile chunk) {
        UploadSession session = requiredUploadSession(uploadSessionId);
        validateUploadableSession(session);
        validateChunk(session, chunkIndex, chunk);

        RLock lock = redissonClient.getLock("lock:upload:chunk:" + uploadSessionId + ":" + chunkIndex);
        lock.lock();
        try {
            // Bitmap 标记已经成功落到对象存储的分片，避免重复写同一块。
            String bitmapKey = uploadBitmapKey(uploadSessionId);
            Boolean alreadyUploaded = stringRedisTemplate.opsForValue().getBit(bitmapKey, chunkIndex);
            if (Boolean.TRUE.equals(alreadyUploaded)) {
                return;
            }

            String chunkKey = chunkObjectKey(session, chunkIndex);
            objectStorageService.putObject(chunkKey, fileInputStream(chunk), chunk.getSize(), "application/octet-stream");
            stringRedisTemplate.opsForValue().setBit(bitmapKey, chunkIndex, true);
            // Bitmap TTL 与上传会话过期时间保持一致，避免 Redis 成为长期数据源。
            refreshBitmapTtl(session);
            session.setUploadedChunks(countUploadedChunks(session));
            session.setUpdatedAt(LocalDateTime.now());
            uploadSessionMapper.updateById(session);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 查询分片上传进度，返回已上传和缺失的分片下标，便于客户端断点续传。
     */
    public MultipartUploadStatusResponse multipartStatus(Long uploadSessionId) {
        UploadSession session = requiredUploadSession(uploadSessionId);
        refreshUploadedChunks(session);
        return toMultipartStatus(session);
    }

    /**
     * 完成分片上传：校验分片完整性，合并对象，创建文件和文档记录，并触发索引任务。
     */
    @Transactional
    public UploadResultResponse completeMultipart(Long uploadSessionId, Long knowledgeBaseId) {
        UploadSession session = requiredUploadSession(uploadSessionId);
        validateUploadableSession(session);
        validateCompleteKnowledgeBase(session, knowledgeBaseId);
        List<Integer> missingChunks = missingChunks(session);
        if (!missingChunks.isEmpty()) {
            throw new BusinessException("分片未上传完整，缺少分片: " + missingChunks);
        }

        RLock lock = redissonClient.getLock("lock:file:dedup:" + session.getFileHash());
        lock.lock();
        try {
            // 完成阶段重新计算所有分片的 SHA-256，不能只信任客户端声明的哈希。
            String actualFileHash = calculateMergedHash(session);
            if (!session.getFileHash().equalsIgnoreCase(actualFileHash)) {
                throw new BusinessException("合并文件 SHA-256 与初始化 SHA-256 不一致");
            }

            // 完成时再次按最终字节哈希检查并发上传中其他会话已经完成的情况。
            FileRecord duplicateAfterHash = findDuplicate(DEFAULT_USER_ID, knowledgeBaseId, actualFileHash);
            if (duplicateAfterHash != null) {
                session.setStatus("COMPLETED");
                session.setCompletedFileId(duplicateAfterHash.getId());
                session.setUpdatedAt(LocalDateTime.now());
                Document document = createDocument(
                        DEFAULT_USER_ID,
                        knowledgeBaseId,
                        duplicateAfterHash,
                        session.getFilename(),
                        session.getContentType());
                session.setCompletedDocumentId(document.getId());
                uploadSessionMapper.updateById(session);
                documentIndexProducer.send(document.getId());
                return new UploadResultResponse(duplicateAfterHash.getId(), document.getId(), "DUPLICATED");
            }

            String objectKey = "files/" + actualFileHash + "/" + safeFilename(session.getFilename());
            // 这里按顺序读取临时分片形成一个连续流，避免把大文件完整加载到内存。
            putMergedObject(session, objectKey);
            FileRecord fileRecord = createFileRecord(
                    DEFAULT_USER_ID,
                    knowledgeBaseId,
                    actualFileHash,
                    objectKey,
                    session.getFilename(),
                    session.getFileSize());

            session.setStatus("COMPLETED");
            session.setCompletedFileId(fileRecord.getId());
            session.setUpdatedAt(LocalDateTime.now());

            Document document = createDocument(
                    DEFAULT_USER_ID,
                    knowledgeBaseId,
                    fileRecord,
                    session.getFilename(),
                    session.getContentType());
            session.setCompletedDocumentId(document.getId());
            uploadSessionMapper.updateById(session);
            documentIndexProducer.send(document.getId());
            return new UploadResultResponse(fileRecord.getId(), document.getId(), "UPLOADED");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按分片顺序流式计算合并后文件的 SHA-256。
     */
    private String calculateMergedHash(UploadSession session) {
        MessageDigest digest = messageDigest("SHA-256", "创建 SHA-256 摘要失败");
        byte[] buffer = new byte[8192];
        for (int i = 0; i < session.getTotalChunks(); i++) {
            try (InputStream inputStream = objectStorageService.getObject(chunkObjectKey(session, i))) {
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            } catch (IOException | RuntimeException ex) {
                throw new BusinessException("读取分片失败: " + i);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 将分片顺序拼成输入流后写入最终对象。
     */
    private void putMergedObject(UploadSession session, String objectKey) {
        try (InputStream inputStream = new ChunkSequenceInputStream(session)) {
            objectStorageService.putObject(objectKey, inputStream, session.getFileSize(), contentType(session.getContentType()));
        } catch (IOException ex) {
            throw new BusinessException("合并上传文件失败");
        }
    }

    /**
     * 读取上传会话，不存在时转换为明确的业务错误。
     */
    private UploadSession requiredUploadSession(Long uploadSessionId) {
        UploadSession session = uploadSessionMapper.selectById(uploadSessionId);
        if (session == null) {
            throw new BusinessException("上传会话不存在");
        }
        return session;
    }

    /**
     * 在当前用户和知识库范围内按完整文件 SHA-256 查找已入库文件。
     */
    private FileRecord findDuplicate(Long userId, Long knowledgeBaseId, String fileHash) {
        return fileRecordMapper.selectOne(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getUserId, userId)
                .eq(FileRecord::getKnowledgeBaseId, knowledgeBaseId)
                .eq(FileRecord::getFileHash, fileHash)
                .last("LIMIT 1"));
    }

    /**
     * 创建文件元数据，文件内容已经在对象存储中落盘。
     */
    private FileRecord createFileRecord(
            Long userId,
            Long knowledgeBaseId,
            String fileHash,
            String objectKey,
            String filename,
            long size
    ) {
        LocalDateTime now = LocalDateTime.now();
        FileRecord fileRecord = new FileRecord();
        fileRecord.setUserId(userId);
        fileRecord.setKnowledgeBaseId(knowledgeBaseId);
        fileRecord.setFilename(safeFilename(filename));
        fileRecord.setFileSize(size);
        fileRecord.setFileHash(fileHash);
        fileRecord.setStorageKey(objectKey);
        fileRecord.setStatus("COMPLETED");
        fileRecord.setCreatedAt(now);
        fileRecordMapper.insert(fileRecord);
        return fileRecord;
    }

    /**
     * 为文件在指定知识库下创建文档记录，并等待异步解析和索引。
     */
    private Document createDocument(
            Long userId,
            Long knowledgeBaseId,
            FileRecord fileRecord,
            String title,
            String contentType
    ) {
        LocalDateTime now = LocalDateTime.now();
        Document document = new Document();
        document.setUserId(userId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileRecordId(fileRecord.getId());
        document.setTitle(safeFilename(title));
        document.setContentType(contentType(contentType));
        document.setPipelineStatus("PENDING");
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentMapper.insert(document);
        return document;
    }

    /**
     * 计算完整 MultipartFile 字节的 SHA-256，作为上传前去重依据。
     */
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
            throw new BusinessException("计算文件 SHA-256 失败");
        }
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

    /**
     * 校验客户端声明的文件大小、分片大小和总分片数是否一致。
     */
    private void validateInitRequest(InitMultipartUploadRequest request) {
        long expectedChunks = (request.fileSize() + request.chunkSize() - 1) / request.chunkSize();
        if (expectedChunks != request.totalChunks()) {
            throw new BusinessException("totalChunks 与 fileSize/chunkSize 不匹配");
        }
    }

    /**
     * 校验上传会话仍处于可写状态；过期会话会落库为 EXPIRED。
     */
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

    /**
     * 防止客户端用一个上传会话完成到另一个知识库。
     */
    private void validateCompleteKnowledgeBase(UploadSession session, Long knowledgeBaseId) {
        if (session.getKnowledgeBaseId() != null && !session.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new BusinessException("完成上传的知识库与初始化上传的知识库不一致");
        }
    }

    /**
     * 校验分片序号和大小，确保最终合并结果可预测。
     */
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

    /**
     * 以 Redis Bitmap 为准刷新 MySQL 中的已上传分片数。
     */
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

    /**
     * 从 Redis Bitmap 读取已经上传成功的分片下标。
     */
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

    /**
     * 从 Redis Bitmap 读取仍然缺失的分片下标。
     */
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

    /**
     * 将上传会话转换为接口响应，避免 Controller 暴露持久化对象。
     */
    private MultipartUploadStatusResponse toMultipartStatus(UploadSession session) {
        List<Integer> uploaded = uploadedChunks(session);
        List<Integer> missing = missingChunks(session);
        return new MultipartUploadStatusResponse(
                session.getId(),
                session.getKnowledgeBaseId(),
                session.getFilename(),
                session.getFileHash(),
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

    /**
     * 统一规范化完整文件 SHA-256。
     */
    private String normalizeSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            throw new BusinessException("文件 SHA-256 不能为空");
        }
        String normalized = sha256.toLowerCase(Locale.ROOT);
        if (normalized.length() != 64 || !isHex(normalized)) {
            throw new BusinessException("文件 SHA-256 格式不正确");
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

    /**
     * 顺序读取上传临时分片的输入流，供对象存储 SDK 流式写入最终文件。
     */
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
