package com.studyagent.modules.storage.application;

import com.studyagent.config.ObjectStorageProperties;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.mq.DocumentIndexProducer;
import com.studyagent.infrastructure.objectstorage.MultipartUploadPart;
import com.studyagent.infrastructure.objectstorage.ObjectStorageService;
import com.studyagent.modules.knowledge.application.DocumentProcessingService;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.infrastructure.DocumentChunkMapper;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import com.studyagent.modules.storage.domain.FileRecord;
import com.studyagent.modules.storage.interfaces.PerformancePipelineComparisonResponse;
import com.studyagent.modules.storage.interfaces.PerformancePipelineStageResponse;
import com.studyagent.modules.storage.interfaces.PerformanceDirectUploadResponse;
import com.studyagent.modules.storage.interfaces.PerformanceMultipartCompleteRequest;
import com.studyagent.modules.storage.interfaces.PerformanceMultipartCompleteResponse;
import com.studyagent.modules.storage.interfaces.PerformanceMultipartInitResponse;
import com.studyagent.modules.storage.interfaces.PerformanceMultipartPartResponse;
import com.studyagent.modules.storage.interfaces.PerformanceUploadComparisonResponse;
import com.studyagent.modules.storage.interfaces.PerformanceUploadStageResponse;
import com.studyagent.modules.storage.infrastructure.FileRecordMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传与文档处理性能测试服务。
 *
 * <p>本服务只服务于本地/演示环境的性能观测，不替代正式上传用例。为了让每次测试都真实写入 RustFS，
 * 这里会为测试产生独立的文件记录和文档记录，并使用测试专用 objectKey 前缀，避免文件去重把后续测试变成秒传。
 * 真实业务上传仍然走 {@link FileUploadService} 的 MD5/SHA256 去重与分片会话状态机。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadPerformanceService {

    private static final Long DEFAULT_USER_ID = KnowledgeBaseService.DEFAULT_USER_ID;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration MQ_WAIT_LOG_INTERVAL = Duration.ofSeconds(5);
    private static final int MAX_CHUNK_CONCURRENCY = 64;
    private static final int S3_MIN_MULTIPART_PART_SIZE_BYTES = 5 * 1024 * 1024;

    private final FileRecordMapper fileRecordMapper;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final ObjectStorageService objectStorageService;
    private final ObjectStorageProperties objectStorageProperties;
    private final DocumentProcessingService documentProcessingService;
    private final DocumentIndexProducer documentIndexProducer;

    /**
     * 比较同一文件在 RustFS 中“单对象直传”和“S3 原生 Multipart Upload”的耗时。
     *
     * <p>triggerIndex=true 时会在入库后发送 RocketMQ 消息，让上传测试也保持与正式链路一致；
     * 但返回耗时只统计到接口入库和消息发送，后续 embedding/ES 索引由处理链路测试专门统计。</p>
     */
    public PerformanceUploadComparisonResponse compareUpload(
            Long knowledgeBaseId,
            MultipartFile file,
            int chunkSizeBytes,
            int chunkConcurrency,
            boolean triggerIndex
    ) {
        validateUploadRequest(knowledgeBaseId, file);
        if (chunkSizeBytes <= 0) {
            throw new BusinessException("chunkSizeBytes 必须大于 0");
        }
        if (chunkConcurrency <= 0 || chunkConcurrency > MAX_CHUNK_CONCURRENCY) {
            throw new BusinessException("chunkConcurrency 必须在 1 到 " + MAX_CHUNK_CONCURRENCY + " 之间");
        }

        String traceId = randomHex(4);
        log.info(
                "[perf:{}] 开始上传方式性能对比: knowledgeBaseId={}, filename={}, multipartSize={}, fileSize={}, chunkConcurrency={}, triggerIndex={}",
                traceId,
                knowledgeBaseId,
                file.getOriginalFilename(),
                chunkSizeBytes,
                file.getSize(),
                chunkConcurrency,
                triggerIndex
        );
        Path tempFile = saveToTempFile(file);
        try {
            SourceFile sourceFile = sourceFile(file, tempFile);
            log.info(
                    "[perf:{}] 性能测试文件已保存到临时文件: path={}, filename={}, contentType={}, size={}",
                    traceId,
                    sourceFile.path(),
                    sourceFile.filename(),
                    sourceFile.contentType(),
                    sourceFile.size()
            );
            int totalChunks = totalChunks(sourceFile.size(), chunkSizeBytes);
            if (totalChunks > 1 && chunkSizeBytes < S3_MIN_MULTIPART_PART_SIZE_BYTES) {
                throw new BusinessException("S3 Multipart Upload 要求除最后一个 part 外每个 part 至少 5MB，请把分片大小调到 5MB 以上");
            }
            int effectiveChunkConcurrency = Math.min(chunkConcurrency, Math.max(1, totalChunks));
            PerformanceUploadStageResponse direct = uploadDirect(traceId, knowledgeBaseId, sourceFile, triggerIndex);
            PerformanceUploadStageResponse multipart =
                    uploadMultipart(
                            traceId,
                            knowledgeBaseId,
                            sourceFile,
                            chunkSizeBytes,
                            effectiveChunkConcurrency,
                            triggerIndex
                    );
            log.info(
                    "[perf:{}] 上传方式性能对比完成: filename={}, totalChunks={}, effectiveChunkConcurrency={}, directTotalMillis={}, multipartTotalMillis={}",
                    traceId,
                    sourceFile.filename(),
                    totalChunks,
                    effectiveChunkConcurrency,
                    direct.totalMillis(),
                    multipart.totalMillis()
            );
            return new PerformanceUploadComparisonResponse(
                    sourceFile.filename(),
                    sourceFile.contentType(),
                    sourceFile.size(),
                    chunkSizeBytes,
                    totalChunks,
                    effectiveChunkConcurrency,
                    triggerIndex,
                    "SERVER_SIDE_COMPARISON：浏览器先把整个文件传到后端，后端再分别直传和 S3 Multipart 到 RustFS。该接口不代表前端并发分片体验。",
                    direct,
                    multipart
            );
        } finally {
            log.info("[perf:{}] 清理上传性能测试临时文件: path={}", traceId, tempFile);
            deleteTempFile(tempFile);
        }
    }

    /**
     * 单请求直传性能测试。
     *
     * <p>这个接口用于和“前端并发分片”作端到端对比：浏览器一次 POST 整个文件到后端，
     * 后端收到后再流式写入 RustFS。大文件会受 spring.servlet.multipart 单请求大小限制。</p>
     */
    public PerformanceDirectUploadResponse uploadDirectFromBrowser(
            Long knowledgeBaseId,
            MultipartFile file,
            boolean triggerIndex
    ) {
        validateUploadRequest(knowledgeBaseId, file);
        String traceId = randomHex(4);
        log.info(
                "[perf:{}] 前端单请求直传测试开始: knowledgeBaseId={}, filename={}, size={}, triggerIndex={}",
                traceId,
                knowledgeBaseId,
                file.getOriginalFilename(),
                file.getSize(),
                triggerIndex
        );
        SourceFile sourceFile = sourceFile(file);
        PerformanceUploadStageResponse direct = uploadDirectFromMultipartRequest(traceId, knowledgeBaseId, file, sourceFile, triggerIndex);
        log.info(
                "[perf:{}] 前端单请求直传测试完成: filename={}, totalMillis={}",
                traceId,
                sourceFile.filename(),
                direct.totalMillis()
        );
        return new PerformanceDirectUploadResponse(
                sourceFile.filename(),
                sourceFile.contentType(),
                sourceFile.size(),
                direct
        );
    }

    /**
     * 初始化真正的前端并发分片上传测试。
     */
    public PerformanceMultipartInitResponse initBrowserMultipart(
            String filename,
            String contentType,
            long fileSize,
            int chunkSizeBytes
    ) {
        if (fileSize <= 0) {
            throw new BusinessException("fileSize 必须大于 0");
        }
        if (chunkSizeBytes <= 0) {
            throw new BusinessException("chunkSizeBytes 必须大于 0");
        }
        int totalChunks = totalChunks(fileSize, chunkSizeBytes);
        if (totalChunks > 1 && chunkSizeBytes < S3_MIN_MULTIPART_PART_SIZE_BYTES) {
            throw new BusinessException("S3 Multipart Upload 要求除最后一个 part 外每个 part 至少 5MB，请把分片大小调到 5MB 以上");
        }
        String safeFilename = safeFilename(filename);
        String safeContentType = contentType(contentType);
        String objectKey = objectKey("browser-multipart", safeFilename);
        String uploadId = objectStorageService.initiateMultipartUpload(objectKey, safeContentType);
        log.info(
                "前端并发分片测试已初始化: filename={}, fileSize={}, chunkSizeBytes={}, totalChunks={}, objectKey={}, uploadId={}",
                safeFilename,
                fileSize,
                chunkSizeBytes,
                totalChunks,
                objectKey,
                uploadId
        );
        return new PerformanceMultipartInitResponse(uploadId, objectKey, chunkSizeBytes, totalChunks);
    }

    /**
     * 接收前端单个 chunk 并直接转发为 S3 uploadPart。
     */
    public PerformanceMultipartPartResponse uploadBrowserMultipartPart(
            String objectKey,
            String uploadId,
            int partNumber,
            MultipartFile chunk
    ) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException("objectKey 不能为空");
        }
        if (uploadId == null || uploadId.isBlank()) {
            throw new BusinessException("uploadId 不能为空");
        }
        if (partNumber <= 0) {
            throw new BusinessException("partNumber 必须大于 0");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new BusinessException("chunk 不能为空");
        }

        long startedAt = System.nanoTime();
        try (InputStream inputStream = chunk.getInputStream()) {
            MultipartUploadPart part = objectStorageService.uploadPart(
                    objectKey,
                    uploadId,
                    partNumber,
                    inputStream,
                    chunk.getSize()
            );
            long uploadMillis = elapsedSince(startedAt).toMillis();
            log.info(
                    "前端分片已上传到 S3 Multipart: objectKey={}, uploadId={}, partNumber={}, size={}, uploadMillis={}",
                    objectKey,
                    uploadId,
                    partNumber,
                    chunk.getSize(),
                    uploadMillis
            );
            return new PerformanceMultipartPartResponse(part.partNumber(), part.eTag(), uploadMillis);
        } catch (IOException ex) {
            throw new BusinessException("读取前端分片失败: " + ex.getMessage());
        }
    }

    /**
     * 完成前端并发分片上传测试，并为最终对象创建 file/document 记录。
     */
    public PerformanceMultipartCompleteResponse completeBrowserMultipart(PerformanceMultipartCompleteRequest request) {
        validateCompleteMultipartRequest(request);
        String traceId = randomHex(4);
        long startedAt = System.nanoTime();
        List<MultipartUploadPart> parts = request.parts().stream()
                .map(part -> new MultipartUploadPart(part.partNumber(), part.eTag()))
                .toList();
        log.info(
                "[perf:{}] 前端并发分片 complete 开始: objectKey={}, uploadId={}, totalChunks={}, browserUploadMillis={}",
                traceId,
                request.objectKey(),
                request.uploadId(),
                request.totalChunks(),
                request.browserUploadMillis()
        );
        long completeStartedAt = System.nanoTime();
        objectStorageService.completeMultipartUpload(request.objectKey(), request.uploadId(), parts);
        Duration completeDuration = elapsedSince(completeStartedAt);

        SourceFile sourceFile = new SourceFile(
                null,
                safeFilename(request.filename()),
                contentType(request.contentType()),
                request.fileSize()
        );
        long databaseStartedAt = System.nanoTime();
        UploadedDocument document = createFileRecordAndDocument(
                traceId,
                request.knowledgeBaseId(),
                sourceFile,
                request.objectKey(),
                "browser-multipart"
        );
        Duration databaseDuration = elapsedSince(databaseStartedAt);
        if (request.triggerIndex()) {
            log.info("[perf:{}] 前端并发分片测试触发文档索引消息: documentId={}", traceId, document.documentId());
            documentIndexProducer.send(document.documentId());
        }
        long totalMillis = elapsedSince(startedAt).toMillis();
        PerformanceUploadStageResponse multipart = new PerformanceUploadStageResponse(
                "BROWSER_MULTIPART",
                document.fileId(),
                document.documentId(),
                request.objectKey(),
                request.uploadId(),
                request.browserUploadMillis(),
                completeDuration.toMillis(),
                databaseDuration.toMillis(),
                totalMillis
        );
        log.info(
                "[perf:{}] 前端并发分片测试完成: fileId={}, documentId={}, browserUploadMillis={}, completeMillis={}, databaseMillis={}, totalMillis={}",
                traceId,
                document.fileId(),
                document.documentId(),
                request.browserUploadMillis(),
                completeDuration.toMillis(),
                databaseDuration.toMillis(),
                totalMillis
        );
        return new PerformanceMultipartCompleteResponse(
                sourceFile.filename(),
                sourceFile.contentType(),
                sourceFile.size(),
                request.chunkSizeBytes(),
                request.totalChunks(),
                request.chunkConcurrency(),
                multipart
        );
    }

    public void abortBrowserMultipart(String objectKey, String uploadId) {
        if (objectKey == null || objectKey.isBlank() || uploadId == null || uploadId.isBlank()) {
            return;
        }
        abortMultipartUploadQuietly("browser", objectKey, uploadId);
    }

    /**
     * 比较传统同步处理和 RocketMQ 解耦处理的耗时。
     *
     * <p>同步链路会在当前请求线程中完成“上传对象 -> 入库 -> 解析 -> 切块 -> 真实 embedding -> ES 写入”；
     * MQ 链路会在“上传对象 -> 入库 -> 发送消息”后记录 responseMillis，然后继续等待消费者完成索引，
     * 从而同时得到用户可见等待时间和最终完成时间。</p>
     */
    public PerformancePipelineComparisonResponse comparePipeline(
            Long knowledgeBaseId,
            MultipartFile file,
            Duration waitTimeout
    ) {
        validateUploadRequest(knowledgeBaseId, file);
        if (waitTimeout.isNegative() || waitTimeout.isZero()) {
            throw new BusinessException("waitTimeout 必须大于 0");
        }

        String traceId = randomHex(4);
        log.info(
                "[perf:{}] 开始处理链路性能对比: knowledgeBaseId={}, filename={}, fileSize={}, waitTimeoutMillis={}",
                traceId,
                knowledgeBaseId,
                file.getOriginalFilename(),
                file.getSize(),
                waitTimeout.toMillis()
        );
        Path tempFile = saveToTempFile(file);
        try {
            SourceFile sourceFile = sourceFile(file, tempFile);
            log.info(
                    "[perf:{}] 处理链路测试文件已保存到临时文件: path={}, filename={}, contentType={}, size={}",
                    traceId,
                    sourceFile.path(),
                    sourceFile.filename(),
                    sourceFile.contentType(),
                    sourceFile.size()
            );

            long syncStartedAt = System.nanoTime();
            log.info("[perf:{}] 同步链路开始: filename={}", traceId, sourceFile.filename());
            UploadedDocument syncDocument = uploadObjectAndCreateDocument(traceId, knowledgeBaseId, sourceFile, "sync");
            long syncProcessingStartedAt = System.nanoTime();
            log.info(
                    "[perf:{}] 同步链路开始文档处理: documentId={}, fileId={}",
                    traceId,
                    syncDocument.documentId(),
                    syncDocument.fileId()
            );
            documentProcessingService.process(syncDocument.documentId());
            Duration syncProcessingDuration = elapsedSince(syncProcessingStartedAt);
            Duration syncTotalDuration = elapsedSince(syncStartedAt);
            DocumentState syncState = requireIndexed(syncDocument.documentId(), traceId);
            log.info(
                    "[perf:{}] 同步链路完成: documentId={}, uploadMillis={}, databaseMillis={}, processingMillis={}, totalMillis={}, chunks={}",
                    traceId,
                    syncDocument.documentId(),
                    syncDocument.uploadMillis(),
                    syncDocument.databaseMillis(),
                    syncProcessingDuration.toMillis(),
                    syncTotalDuration.toMillis(),
                    syncState.childChunkCount()
            );

            long mqStartedAt = System.nanoTime();
            log.info("[perf:{}] RocketMQ 解耦链路开始: filename={}", traceId, sourceFile.filename());
            UploadedDocument mqDocument = uploadObjectAndCreateDocument(traceId, knowledgeBaseId, sourceFile, "rocketmq");
            long mqSendStartedAt = System.nanoTime();
            log.info(
                    "[perf:{}] RocketMQ 解耦链路发送索引消息: documentId={}, fileId={}",
                    traceId,
                    mqDocument.documentId(),
                    mqDocument.fileId()
            );
            documentIndexProducer.send(mqDocument.documentId());
            Duration mqSendDuration = elapsedSince(mqSendStartedAt);
            Duration mqResponseDuration = elapsedSince(mqStartedAt);
            log.info(
                    "[perf:{}] RocketMQ 解耦链路请求侧完成: documentId={}, responseMillis={}, messageMillis={}, waitTimeoutMillis={}",
                    traceId,
                    mqDocument.documentId(),
                    mqResponseDuration.toMillis(),
                    mqSendDuration.toMillis(),
                    waitTimeout.toMillis()
            );
            DocumentState mqState = waitUntilIndexed(mqDocument.documentId(), waitTimeout, mqStartedAt, traceId);
            log.info(
                    "[perf:{}] RocketMQ 解耦链路最终索引完成: documentId={}, responseMillis={}, indexedMillis={}, chunks={}",
                    traceId,
                    mqDocument.documentId(),
                    mqResponseDuration.toMillis(),
                    mqState.observedSinceStartedMillis(),
                    mqState.childChunkCount()
            );

            PerformancePipelineStageResponse synchronous = new PerformancePipelineStageResponse(
                    syncDocument.fileId(),
                    syncDocument.documentId(),
                    syncDocument.uploadMillis(),
                    syncDocument.databaseMillis(),
                    0,
                    syncProcessingDuration.toMillis(),
                    syncTotalDuration.toMillis(),
                    syncTotalDuration.toMillis(),
                    syncState.parseStatus(),
                    syncState.indexStatus(),
                    syncState.childChunkCount(),
                    syncState.errorMessage()
            );
            PerformancePipelineStageResponse rocketMq = new PerformancePipelineStageResponse(
                    mqDocument.fileId(),
                    mqDocument.documentId(),
                    mqDocument.uploadMillis(),
                    mqDocument.databaseMillis(),
                    mqSendDuration.toMillis(),
                    0,
                    mqResponseDuration.toMillis(),
                    mqState.observedSinceStartedMillis(),
                    mqState.parseStatus(),
                    mqState.indexStatus(),
                    mqState.childChunkCount(),
                    mqState.errorMessage()
            );

            return new PerformancePipelineComparisonResponse(
                    sourceFile.filename(),
                    sourceFile.contentType(),
                    sourceFile.size(),
                    waitTimeout.toMillis(),
                    synchronous,
                    rocketMq
            );
        } finally {
            log.info("[perf:{}] 清理处理链路性能测试临时文件: path={}", traceId, tempFile);
            deleteTempFile(tempFile);
        }
    }

    private PerformanceUploadStageResponse uploadDirect(
            String traceId,
            Long knowledgeBaseId,
            SourceFile sourceFile,
            boolean triggerIndex
    ) {
        long startedAt = System.nanoTime();
        String objectKey = objectKey("upload-direct", sourceFile.filename());
        long uploadStartedAt = System.nanoTime();
        log.info(
                "[perf:{}] 单文件直传开始: knowledgeBaseId={}, objectKey={}, size={}, contentType={}",
                traceId,
                knowledgeBaseId,
                objectKey,
                sourceFile.size(),
                sourceFile.contentType()
        );
        try (InputStream inputStream = Files.newInputStream(sourceFile.path())) {
            objectStorageService.putObject(objectKey, inputStream, sourceFile.size(), sourceFile.contentType());
        } catch (IOException ex) {
            throw new BusinessException("直传测试读取临时文件失败: " + ex.getMessage());
        }
        Duration uploadDuration = elapsedSince(uploadStartedAt);
        log.info("[perf:{}] 单文件直传对象写入完成: objectKey={}, uploadMillis={}", traceId, objectKey, uploadDuration.toMillis());

        long databaseStartedAt = System.nanoTime();
        UploadedDocument document =
                createFileRecordAndDocument(traceId, knowledgeBaseId, sourceFile, objectKey, "upload-direct");
        Duration databaseDuration = elapsedSince(databaseStartedAt);
        if (triggerIndex) {
            log.info("[perf:{}] 单文件直传触发文档索引消息: documentId={}", traceId, document.documentId());
            documentIndexProducer.send(document.documentId());
        }

        long totalMillis = elapsedSince(startedAt).toMillis();
        log.info(
                "[perf:{}] 单文件直传测试完成: fileId={}, documentId={}, uploadMillis={}, databaseMillis={}, totalMillis={}",
                traceId,
                document.fileId(),
                document.documentId(),
                uploadDuration.toMillis(),
                databaseDuration.toMillis(),
                totalMillis
        );
        return new PerformanceUploadStageResponse(
                "DIRECT",
                document.fileId(),
                document.documentId(),
                objectKey,
                null,
                uploadDuration.toMillis(),
                0,
                databaseDuration.toMillis(),
                totalMillis
        );
    }

    private PerformanceUploadStageResponse uploadDirectFromMultipartRequest(
            String traceId,
            Long knowledgeBaseId,
            MultipartFile file,
            SourceFile sourceFile,
            boolean triggerIndex
    ) {
        long startedAt = System.nanoTime();
        String objectKey = objectKey("browser-direct", sourceFile.filename());
        long uploadStartedAt = System.nanoTime();
        log.info(
                "[perf:{}] 前端单请求直传写入 RustFS 开始: knowledgeBaseId={}, objectKey={}, size={}, contentType={}",
                traceId,
                knowledgeBaseId,
                objectKey,
                sourceFile.size(),
                sourceFile.contentType()
        );
        try (InputStream inputStream = file.getInputStream()) {
            objectStorageService.putObject(objectKey, inputStream, sourceFile.size(), sourceFile.contentType());
        } catch (IOException ex) {
            throw new BusinessException("前端单请求直传读取文件失败: " + ex.getMessage());
        }
        Duration uploadDuration = elapsedSince(uploadStartedAt);
        log.info("[perf:{}] 前端单请求直传写入 RustFS 完成: objectKey={}, uploadMillis={}", traceId, objectKey, uploadDuration.toMillis());

        long databaseStartedAt = System.nanoTime();
        UploadedDocument document =
                createFileRecordAndDocument(traceId, knowledgeBaseId, sourceFile, objectKey, "browser-direct");
        Duration databaseDuration = elapsedSince(databaseStartedAt);
        if (triggerIndex) {
            log.info("[perf:{}] 前端单请求直传触发文档索引消息: documentId={}", traceId, document.documentId());
            documentIndexProducer.send(document.documentId());
        }

        long totalMillis = elapsedSince(startedAt).toMillis();
        log.info(
                "[perf:{}] 前端单请求直传测试完成: fileId={}, documentId={}, uploadMillis={}, databaseMillis={}, totalMillis={}",
                traceId,
                document.fileId(),
                document.documentId(),
                uploadDuration.toMillis(),
                databaseDuration.toMillis(),
                totalMillis
        );
        return new PerformanceUploadStageResponse(
                "BROWSER_DIRECT",
                document.fileId(),
                document.documentId(),
                objectKey,
                null,
                uploadDuration.toMillis(),
                0,
                databaseDuration.toMillis(),
                totalMillis
        );
    }

    private PerformanceUploadStageResponse uploadMultipart(
            String traceId,
            Long knowledgeBaseId,
            SourceFile sourceFile,
            int chunkSizeBytes,
            int chunkConcurrency,
            boolean triggerIndex
    ) {
        long startedAt = System.nanoTime();
        String objectKey = objectKey("upload-multipart", sourceFile.filename());
        String uploadId = objectStorageService.initiateMultipartUpload(objectKey, sourceFile.contentType());
        int totalChunks = totalChunks(sourceFile.size(), chunkSizeBytes);
        MultipartUploadPart[] uploadedParts = new MultipartUploadPart[totalChunks];

        long chunkUploadStartedAt = System.nanoTime();
        log.info(
                "[perf:{}] S3 Multipart Upload 开始: knowledgeBaseId={}, objectKey={}, uploadId={}, totalChunks={}, chunkSizeBytes={}, concurrency={}, sourceSize={}",
                traceId,
                knowledgeBaseId,
                objectKey,
                uploadId,
                totalChunks,
                chunkSizeBytes,
                chunkConcurrency,
                sourceFile.size()
        );

        /*
         * 这里使用 S3/RustFS 原生 Multipart Upload：先创建 uploadId，再并发 uploadPart，
         * 最后 completeMultipartUpload。这样测到的是对象存储服务端原生组装能力，而不是应用层
         * 把临时对象读回来再写一遍的伪分片合并成本。
         */
        ExecutorService executorService = Executors.newFixedThreadPool(chunkConcurrency);
        CompletionService<PartUploadResult> completionService = new ExecutorCompletionService<>(executorService);
        boolean completed = false;
        try {
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                final int currentChunkIndex = chunkIndex;
                completionService.submit(() -> uploadPart(sourceFile, objectKey, uploadId, currentChunkIndex, chunkSizeBytes));
            }
            for (int finishedChunks = 0; finishedChunks < totalChunks; finishedChunks++) {
                PartUploadResult result = takeUploadedPart(completionService, executorService);
                uploadedParts[result.chunkIndex()] = result.part();
                if (shouldLogChunkProgress(finishedChunks, totalChunks)) {
                    log.info(
                            "[perf:{}] S3 Multipart Upload part 进度: uploadId={}, finishedParts={}/{}, lastPartNumber={}, lastPartBytes={}",
                            traceId,
                            uploadId,
                            finishedChunks + 1,
                            totalChunks,
                            result.part().partNumber(),
                            result.partLength()
                    );
                }
            }

            Duration chunkUploadDuration = elapsedSince(chunkUploadStartedAt);
            log.info(
                    "[perf:{}] S3 Multipart Upload part 上传完成: uploadId={}, totalParts={}, concurrency={}, uploadMillis={}",
                    traceId,
                    uploadId,
                    totalChunks,
                    chunkConcurrency,
                    chunkUploadDuration.toMillis()
            );

            long completeStartedAt = System.nanoTime();
            log.info("[perf:{}] S3 Multipart Upload complete 开始: uploadId={}, objectKey={}", traceId, uploadId, objectKey);
            objectStorageService.completeMultipartUpload(objectKey, uploadId, List.of(uploadedParts));
            completed = true;
            Duration completeDuration = elapsedSince(completeStartedAt);
            log.info("[perf:{}] S3 Multipart Upload complete 完成: objectKey={}, completeMillis={}", traceId, objectKey, completeDuration.toMillis());

            long databaseStartedAt = System.nanoTime();
            UploadedDocument document =
                    createFileRecordAndDocument(traceId, knowledgeBaseId, sourceFile, objectKey, "upload-multipart");
            Duration databaseDuration = elapsedSince(databaseStartedAt);
            if (triggerIndex) {
                log.info("[perf:{}] S3 Multipart Upload 测试触发文档索引消息: documentId={}", traceId, document.documentId());
                documentIndexProducer.send(document.documentId());
            }

            long totalMillis = elapsedSince(startedAt).toMillis();
            log.info(
                    "[perf:{}] S3 Multipart Upload 测试完成: fileId={}, documentId={}, uploadMillis={}, completeMillis={}, databaseMillis={}, totalMillis={}",
                    traceId,
                    document.fileId(),
                    document.documentId(),
                    chunkUploadDuration.toMillis(),
                    completeDuration.toMillis(),
                    databaseDuration.toMillis(),
                    totalMillis
            );
            return new PerformanceUploadStageResponse(
                    "MULTIPART",
                    document.fileId(),
                    document.documentId(),
                    objectKey,
                    uploadId,
                    chunkUploadDuration.toMillis(),
                    completeDuration.toMillis(),
                    databaseDuration.toMillis(),
                    totalMillis
            );
        } finally {
            executorService.shutdownNow();
            if (!completed) {
                abortMultipartUploadQuietly(traceId, objectKey, uploadId);
            }
        }
    }

    private PartUploadResult takeUploadedPart(
            CompletionService<PartUploadResult> completionService,
            ExecutorService executorService
    ) {
        try {
            return completionService.take().get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
            throw new BusinessException("并发分片上传被中断");
        } catch (ExecutionException ex) {
            executorService.shutdownNow();
            Throwable cause = ex.getCause();
            if (cause instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException("并发分片上传失败: " + cause.getMessage());
        }
    }

    private PartUploadResult uploadPart(
            SourceFile sourceFile,
            String objectKey,
            String uploadId,
            int chunkIndex,
            int chunkSizeBytes
    ) {
        long offset = (long) chunkIndex * chunkSizeBytes;
        long partLength = Math.min(chunkSizeBytes, sourceFile.size() - offset);
        int partNumber = chunkIndex + 1;
        try (InputStream inputStream = Files.newInputStream(sourceFile.path())) {
            skipFully(inputStream, offset);
            MultipartUploadPart part = objectStorageService.uploadPart(
                    objectKey,
                    uploadId,
                    partNumber,
                    new LimitedInputStream(inputStream, partLength),
                    partLength
            );
            return new PartUploadResult(chunkIndex, part, partLength);
        } catch (IOException ex) {
            throw new BusinessException("上传测试分片写入失败: " + chunkIndex + ", " + ex.getMessage());
        }
    }

    private void abortMultipartUploadQuietly(String traceId, String objectKey, String uploadId) {
        try {
            objectStorageService.abortMultipartUpload(objectKey, uploadId);
            log.info("[perf:{}] S3 Multipart Upload 已中止: objectKey={}, uploadId={}", traceId, objectKey, uploadId);
        } catch (RuntimeException ex) {
            log.warn("[perf:{}] S3 Multipart Upload 中止失败: objectKey={}, uploadId={}", traceId, objectKey, uploadId, ex);
        }
    }

    private UploadedDocument uploadObjectAndCreateDocument(
            String traceId,
            Long knowledgeBaseId,
            SourceFile sourceFile,
            String mode
    ) {
        String objectKey = objectKey("pipeline-" + mode, sourceFile.filename());
        long uploadStartedAt = System.nanoTime();
        log.info(
                "[perf:{}] 处理链路对象上传开始: mode={}, knowledgeBaseId={}, objectKey={}, size={}",
                traceId,
                mode,
                knowledgeBaseId,
                objectKey,
                sourceFile.size()
        );
        try (InputStream inputStream = Files.newInputStream(sourceFile.path())) {
            objectStorageService.putObject(objectKey, inputStream, sourceFile.size(), sourceFile.contentType());
        } catch (IOException ex) {
            throw new BusinessException("处理链路测试上传对象失败: " + ex.getMessage());
        }
        long uploadMillis = elapsedSince(uploadStartedAt).toMillis();
        log.info("[perf:{}] 处理链路对象上传完成: mode={}, objectKey={}, uploadMillis={}", traceId, mode, objectKey, uploadMillis);

        long databaseStartedAt = System.nanoTime();
        UploadedDocument document =
                createFileRecordAndDocument(traceId, knowledgeBaseId, sourceFile, objectKey, "pipeline-" + mode);
        long databaseMillis = elapsedSince(databaseStartedAt).toMillis();
        log.info(
                "[perf:{}] 处理链路入库完成: mode={}, fileId={}, documentId={}, databaseMillis={}",
                traceId,
                mode,
                document.fileId(),
                document.documentId(),
                databaseMillis
        );
        return new UploadedDocument(document.fileId(), document.documentId(), uploadMillis, databaseMillis);
    }

    /**
     * 写入测试专用 file/document 记录。
     *
     * <p>这里使用唯一测试哈希，而不是源文件真实哈希，是为了每次性能测试都执行物理上传和索引，
     * 避免命中正式上传的文件去重逻辑。该行为只存在于 /api/performance 下，正式上传接口仍使用真实哈希。</p>
     */
    private UploadedDocument createFileRecordAndDocument(
            String traceId,
            Long knowledgeBaseId,
            SourceFile sourceFile,
            String objectKey,
            String mode
    ) {
        LocalDateTime now = LocalDateTime.now();
        FileRecord fileRecord = new FileRecord();
        fileRecord.setUserId(DEFAULT_USER_ID);
        fileRecord.setMd5(randomHex(16));
        fileRecord.setSha256(randomHex(32));
        fileRecord.setBucket(objectStorageProperties.bucket());
        fileRecord.setObjectKey(objectKey);
        fileRecord.setFilename(sourceFile.filename());
        fileRecord.setContentType(sourceFile.contentType());
        fileRecord.setSize(sourceFile.size());
        fileRecord.setStorageProvider("RUSTFS_S3");
        fileRecord.setStatus("PERF_" + mode.toUpperCase(Locale.ROOT).replace("-", "_"));
        fileRecord.setCreatedAt(now);
        fileRecord.setUpdatedAt(now);
        fileRecordMapper.insert(fileRecord);
        log.info(
                "[perf:{}] 性能测试文件记录已入库: mode={}, fileId={}, bucket={}, objectKey={}, filename={}, size={}",
                traceId,
                mode,
                fileRecord.getId(),
                fileRecord.getBucket(),
                objectKey,
                sourceFile.filename(),
                sourceFile.size()
        );

        Document document = new Document();
        document.setUserId(DEFAULT_USER_ID);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileId(fileRecord.getId());
        document.setTitle(sourceFile.filename());
        document.setSourceType("PERFORMANCE");
        document.setParseStatus("UPLOADED");
        document.setIndexStatus("PENDING");
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentMapper.insert(document);
        log.info(
                "[perf:{}] 性能测试文档记录已入库: mode={}, documentId={}, fileId={}, knowledgeBaseId={}, parseStatus={}, indexStatus={}",
                traceId,
                mode,
                document.getId(),
                fileRecord.getId(),
                knowledgeBaseId,
                document.getParseStatus(),
                document.getIndexStatus()
        );
        return new UploadedDocument(fileRecord.getId(), document.getId(), 0, 0);
    }

    private DocumentState waitUntilIndexed(Long documentId, Duration timeout, long startedAtNanos, String traceId) {
        long waitStartedAt = System.nanoTime();
        long lastLogAt = 0L;
        log.info("[perf:{}] 开始等待 RocketMQ 消费完成索引: documentId={}, timeoutMillis={}", traceId, documentId, timeout.toMillis());
        while (Duration.ofNanos(System.nanoTime() - waitStartedAt).compareTo(timeout) < 0) {
            Document document = documentMapper.selectById(documentId);
            if (document != null && "INDEXED".equals(document.getIndexStatus())) {
                log.info(
                        "[perf:{}] RocketMQ 消费索引完成: documentId={}, parseStatus={}, indexStatus={}, waitedMillis={}",
                        traceId,
                        documentId,
                        document.getParseStatus(),
                        document.getIndexStatus(),
                        elapsedSince(waitStartedAt).toMillis()
                );
                return documentState(document, startedAtNanos);
            }
            if (document != null && "FAILED".equals(document.getIndexStatus())) {
                log.warn(
                        "[perf:{}] RocketMQ 消费索引失败: documentId={}, parseStatus={}, indexStatus={}, error={}",
                        traceId,
                        documentId,
                        document.getParseStatus(),
                        document.getIndexStatus(),
                        document.getErrorMessage()
                );
                throw new BusinessException("文档索引失败: documentId=" + documentId + ", error=" + document.getErrorMessage());
            }
            long now = System.nanoTime();
            if (lastLogAt == 0L || Duration.ofNanos(now - lastLogAt).compareTo(MQ_WAIT_LOG_INTERVAL) >= 0) {
                if (document == null) {
                    log.info("[perf:{}] 等待 RocketMQ 索引中: documentId={} 尚未查询到文档记录", traceId, documentId);
                } else {
                    log.info(
                            "[perf:{}] 等待 RocketMQ 索引中: documentId={}, parseStatus={}, indexStatus={}, waitedMillis={}",
                            traceId,
                            documentId,
                            document.getParseStatus(),
                            document.getIndexStatus(),
                            elapsedSince(waitStartedAt).toMillis()
                    );
                }
                lastLogAt = now;
            }
            sleepQuietly();
        }
        log.warn("[perf:{}] 等待 RocketMQ 文档索引超时: documentId={}, timeoutMillis={}", traceId, documentId, timeout.toMillis());
        throw new BusinessException("等待 RocketMQ 文档索引超时: documentId=" + documentId + ", timeout=" + timeout);
    }

    private DocumentState requireIndexed(Long documentId, String traceId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            log.warn("[perf:{}] 同步链路处理完成后未找到文档: documentId={}", traceId, documentId);
            throw new BusinessException("文档不存在: " + documentId);
        }
        if (!"INDEXED".equals(document.getIndexStatus())) {
            log.warn(
                    "[perf:{}] 同步链路未完成索引: documentId={}, parseStatus={}, indexStatus={}, error={}",
                    traceId,
                    documentId,
                    document.getParseStatus(),
                    document.getIndexStatus(),
                    document.getErrorMessage()
            );
            throw new BusinessException("同步处理未完成索引: documentId=" + documentId
                    + ", parseStatus=" + document.getParseStatus()
                    + ", indexStatus=" + document.getIndexStatus()
                    + ", error=" + document.getErrorMessage());
        }
        log.info(
                "[perf:{}] 同步链路索引状态确认完成: documentId={}, parseStatus={}, indexStatus={}",
                traceId,
                documentId,
                document.getParseStatus(),
                document.getIndexStatus()
        );
        return documentState(document, System.nanoTime());
    }

    private DocumentState documentState(Document document, long startedAtNanos) {
        return new DocumentState(
                document.getParseStatus(),
                document.getIndexStatus(),
                document.getErrorMessage(),
                documentChunkMapper.countByDocumentId(document.getId()),
                elapsedSince(startedAtNanos).toMillis()
        );
    }

    private SourceFile sourceFile(MultipartFile file, Path tempFile) {
        try {
            return new SourceFile(
                    tempFile,
                    safeFilename(file.getOriginalFilename()),
                    contentType(file.getContentType()),
                    Files.size(tempFile)
            );
        } catch (IOException ex) {
            throw new BusinessException("读取测试临时文件大小失败: " + ex.getMessage());
        }
    }

    private SourceFile sourceFile(MultipartFile file) {
        return new SourceFile(
                null,
                safeFilename(file.getOriginalFilename()),
                contentType(file.getContentType()),
                file.getSize()
        );
    }

    private Path saveToTempFile(MultipartFile file) {
        try {
            Path tempFile = Files.createTempFile("study-agent-performance-", ".upload");
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (IOException ex) {
            throw new BusinessException("保存性能测试上传文件失败: " + ex.getMessage());
        }
    }

    private void validateUploadRequest(Long knowledgeBaseId, MultipartFile file) {
        if (knowledgeBaseId == null) {
            throw new BusinessException("knowledgeBaseId 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("测试文件不能为空");
        }
    }

    private void validateCompleteMultipartRequest(PerformanceMultipartCompleteRequest request) {
        if (request == null) {
            throw new BusinessException("完成分片请求不能为空");
        }
        if (request.knowledgeBaseId() == null) {
            throw new BusinessException("knowledgeBaseId 不能为空");
        }
        if (request.objectKey() == null || request.objectKey().isBlank()) {
            throw new BusinessException("objectKey 不能为空");
        }
        if (request.uploadId() == null || request.uploadId().isBlank()) {
            throw new BusinessException("uploadId 不能为空");
        }
        if (request.fileSize() <= 0) {
            throw new BusinessException("fileSize 必须大于 0");
        }
        if (request.totalChunks() <= 0) {
            throw new BusinessException("totalChunks 必须大于 0");
        }
        if (request.parts() == null || request.parts().size() != request.totalChunks()) {
            throw new BusinessException("parts 数量与 totalChunks 不一致");
        }
    }

    private String objectKey(String mode, String filename) {
        return "perf/" + mode + "/" + LocalDateTime.now().toString().replace(":", "")
                + "-" + randomHex(8) + "/" + filename;
    }

    private int totalChunks(long fileSize, int chunkSizeBytes) {
        return Math.toIntExact((fileSize + chunkSizeBytes - 1) / chunkSizeBytes);
    }

    private boolean shouldLogChunkProgress(int chunkIndex, int totalChunks) {
        return chunkIndex == 0 || chunkIndex == totalChunks - 1 || (chunkIndex + 1) % 10 == 0;
    }

    private void skipFully(InputStream inputStream, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = inputStream.skip(remaining);
            if (skipped <= 0) {
                if (inputStream.read() == -1) {
                    throw new IOException("分片偏移超出文件大小");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("等待文档索引被中断");
        }
    }

    private Duration elapsedSince(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    private String contentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return contentType;
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "performance-upload.bin";
        }
        return filename.replace("\\", "_").replace("/", "_");
    }

    private String randomHex(int bytes) {
        byte[] values = new byte[bytes];
        RANDOM.nextBytes(values);
        return HexFormat.of().formatHex(values);
    }

    private void deleteTempFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new BusinessException("删除性能测试临时文件失败: " + ex.getMessage());
        }
    }

    private record SourceFile(
            Path path,
            String filename,
            String contentType,
            long size
    ) {
    }

    private record UploadedDocument(
            Long fileId,
            Long documentId,
            long uploadMillis,
            long databaseMillis
    ) {
    }

    private record DocumentState(
            String parseStatus,
            String indexStatus,
            String errorMessage,
            int childChunkCount,
            long observedSinceStartedMillis
    ) {
    }

    private record PartUploadResult(
            int chunkIndex,
            MultipartUploadPart part,
            long partLength
    ) {
    }

    /**
     * 限制从当前输入流最多读取指定字节数，避免上传某个分片时把后续内容一并写入对象存储。
     */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = delegate.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read != -1) {
                remaining -= read;
            }
            return read;
        }
    }

}
