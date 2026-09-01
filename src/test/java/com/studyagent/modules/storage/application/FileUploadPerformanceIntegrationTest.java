package com.studyagent.modules.storage.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.studyagent.config.ObjectStorageProperties;
import com.studyagent.common.response.ApiResponse;
import com.studyagent.infrastructure.objectstorage.ObjectStorageService;
import com.studyagent.modules.knowledge.application.DocumentProcessingService;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.domain.KnowledgeBase;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import com.studyagent.modules.storage.domain.FileRecord;
import com.studyagent.modules.storage.infrastructure.FileRecordMapper;
import com.studyagent.modules.storage.interfaces.InitMultipartUploadResponse;
import com.studyagent.modules.storage.interfaces.UploadResultResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 文件上传链路的手动性能集成测试。
 *
 * <p>这类测试依赖本地 MySQL、Redis、RustFS、RocketMQ 和 Elasticsearch，且会真实写入对象存储和业务表。
 * 因此默认跳过，避免普通单元测试因为环境或机器性能产生不稳定结果。需要压测时执行：</p>
 *
 * <pre>
 * mvn -Dtest=FileUploadPerformanceIntegrationTest -Dstudyagent.perf.enabled=true test
 * </pre>
 *
 * <p>可选参数：</p>
 * <pre>
 * -Dstudyagent.perf.source-file=D:\tmp\your-document.pdf
 * -Dstudyagent.perf.upload-file-size-mb=8
 * -Dstudyagent.perf.pipeline-file-size-mb=1
 * -Dstudyagent.perf.chunk-size-mb=4
 * -Dstudyagent.perf.wait-indexed-seconds=180
 * </pre>
 *
 * <p>默认会复制源文件并追加一个很小的唯一标记，目的是绕过文件去重，让“直传”和“分片上传”
 * 都真实走上传路径。如果你要保持源文件字节完全不变，请分别提供两个不同文件。</p>
 */
@Tag("performance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfSystemProperty(named = "studyagent.perf.enabled", matches = "true")
@TestPropertySource(properties = {
        "study-agent.canal.enabled=false",
        "study-agent.elasticsearch.chunk-index=study-agent-perf-chunks",
        "spring.servlet.multipart.max-file-size=512MB",
        "spring.servlet.multipart.max-request-size=540MB"
})
class FileUploadPerformanceIntegrationTest {

    private static final ParameterizedTypeReference<ApiResponse<UploadResultResponse>> UPLOAD_RESPONSE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<InitMultipartUploadResponse>> INIT_MULTIPART_RESPONSE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private KnowledgeBaseService knowledgeBaseService;
    @Autowired
    private DocumentMapper documentMapper;
    @Autowired
    private FileRecordMapper fileRecordMapper;
    @Autowired
    private ObjectStorageService objectStorageService;
    @Autowired
    private ObjectStorageProperties objectStorageProperties;
    @Autowired
    private DocumentProcessingService documentProcessingService;

    @Test
    @Order(2)
    void compareSingleUploadAndMultipartUploadForLargeFile() throws Exception {
        Long knowledgeBaseId = defaultKnowledgeBaseId();
        int chunkSizeBytes = megabytes("studyagent.perf.chunk-size-mb", 4);

        BenchmarkFile singleFile = benchmarkFile(
                "studyagent.perf.single-upload-source-file",
                "studyagent.perf.upload-source-file",
                "studyagent.perf.upload-file-size-mb",
                8,
                "single-upload-" + UUID.randomUUID()
        );
        BenchmarkFile multipartFile = benchmarkFile(
                "studyagent.perf.multipart-upload-source-file",
                "studyagent.perf.upload-source-file",
                "studyagent.perf.upload-file-size-mb",
                8,
                "multipart-upload-" + UUID.randomUUID()
        );
        try {
            TimedResult<UploadResultResponse> singleUpload = timed(() ->
                    uploadSingle(knowledgeBaseId, singleFile)
            );

            FileHashes multipartHashes = hashes(multipartFile.path());
            TimedResult<UploadResultResponse> multipartUpload = timed(() ->
                    uploadByChunks(knowledgeBaseId, multipartFile, multipartHashes, chunkSizeBytes)
            );

            System.out.println("""

                    [RustFS 大文件上传耗时对比]
                    fileSize=%s, chunkSize=%s, totalChunks=%d
                    singleUpload.response=%s, documentId=%d
                    multipartUpload.response=%s, documentId=%d
                    说明：这里只统计上传接口从发起到返回的耗时。接口返回后，RocketMQ 消费端会继续按真实链路做解析、embedding 和 ES 索引。
                    """.formatted(
                    bytes(singleFile.size()),
                    bytes(chunkSizeBytes),
                    totalChunks(multipartFile.size(), chunkSizeBytes),
                    duration(singleUpload.duration()),
                    singleUpload.value().documentId(),
                    duration(multipartUpload.duration()),
                    multipartUpload.value().documentId()
            ));

            assertThat(singleUpload.duration()).isPositive();
            assertThat(multipartUpload.duration()).isPositive();
        } finally {
            singleFile.deleteIfTemporary();
            multipartFile.deleteIfTemporary();
        }
    }

    @Test
    @Order(1)
    void compareSynchronousIndexingAndRocketMqDecoupledUploadReturnTime() throws Exception {
        Long knowledgeBaseId = defaultKnowledgeBaseId();
        Duration waitTimeout = Duration.ofSeconds(Long.getLong("studyagent.perf.wait-indexed-seconds", 180L));

        BenchmarkFile synchronousFile = benchmarkFile(
                "studyagent.perf.sync-source-file",
                "studyagent.perf.pipeline-source-file",
                "studyagent.perf.pipeline-file-size-mb",
                1,
                "sync-index-" + UUID.randomUUID()
        );
        BenchmarkFile rocketMqFile = benchmarkFile(
                "studyagent.perf.rocketmq-source-file",
                "studyagent.perf.pipeline-source-file",
                "studyagent.perf.pipeline-file-size-mb",
                1,
                "rocketmq-index-" + UUID.randomUUID()
        );
        try {
            long synchronousStartedAt = System.nanoTime();
            UploadedDocument uploadedDocument = uploadFileAndCreateDocumentWithoutMq(knowledgeBaseId, synchronousFile);
            long indexingStartedAt = System.nanoTime();
            documentProcessingService.process(uploadedDocument.documentId());
            Duration synchronousIndexingDuration = elapsedSince(indexingStartedAt);
            Duration synchronousTotalDuration = elapsedSince(synchronousStartedAt);
            DocumentState synchronousState = requireIndexed(uploadedDocument.documentId());

            long rocketMqStartedAt = System.nanoTime();
            UploadResultResponse rocketMqResponse = uploadSingle(knowledgeBaseId, rocketMqFile);
            Duration rocketMqResponseDuration = elapsedSince(rocketMqStartedAt);
            DocumentState rocketMqState = waitUntilIndexed(rocketMqResponse.documentId(), waitTimeout);
            Duration rocketMqEventualDuration = Duration.ofNanos(rocketMqState.observedAtNanos() - rocketMqStartedAt);

            System.out.println("""

                    [同步处理 vs RocketMQ 解耦耗时对比]
                    fileSize=%s
                    traditional.uploadAndDb=%s
                    traditional.embeddingAndEs=%s
                    traditional.requestThreadTotal=%s, documentStatus=%s/%s
                    rocketmq.uploadResponse=%s
                    rocketmq.eventualIndexed=%s, documentStatus=%s/%s
                    说明：traditional.requestThreadTotal 模拟“上传文件 -> 入库 -> 解析/切块/embedding/ES 索引”都在请求线程内完成。
                         rocketmq.uploadResponse 是当前生产接口的用户可见等待时间；eventualIndexed 是后台 MQ 消费完成后的真实端到端时间。
                    """.formatted(
                    bytes(synchronousFile.size()),
                    duration(uploadedDocument.uploadAndDbDuration()),
                    duration(synchronousIndexingDuration),
                    duration(synchronousTotalDuration),
                    synchronousState.parseStatus(),
                    synchronousState.indexStatus(),
                    duration(rocketMqResponseDuration),
                    duration(rocketMqEventualDuration),
                    rocketMqState.parseStatus(),
                    rocketMqState.indexStatus()
            ));

            assertThat(synchronousState.indexStatus()).isEqualTo("INDEXED");
            assertThat(rocketMqState.indexStatus()).isEqualTo("INDEXED");
            assertThat(rocketMqResponseDuration).isLessThan(synchronousTotalDuration);
        } finally {
            synchronousFile.deleteIfTemporary();
            rocketMqFile.deleteIfTemporary();
        }
    }

    private Long defaultKnowledgeBaseId() {
        return knowledgeBaseService.getOrCreateDefault(KnowledgeBaseService.DEFAULT_USER_ID).getId();
    }

    private UploadResultResponse uploadSingle(Long knowledgeBaseId, BenchmarkFile file) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("knowledgeBaseId", knowledgeBaseId.toString());
        body.add("file", namedFileResource(file.path(), file.filename()));
        return postMultipart("/api/files/upload", body, UPLOAD_RESPONSE, "普通上传");
    }

    private UploadResultResponse uploadByChunks(
            Long knowledgeBaseId,
            BenchmarkFile file,
            FileHashes hashes,
            int chunkSizeBytes
    ) throws Exception {
        long fileSize = file.size();
        int totalChunks = totalChunks(fileSize, chunkSizeBytes);
        MultiValueMap<String, Object> initBody = new LinkedMultiValueMap<>();
        initBody.add("knowledgeBaseId", knowledgeBaseId.toString());
        initBody.add("filename", file.filename());
        initBody.add("contentType", file.contentType());
        initBody.add("md5", hashes.md5());
        initBody.add("sha256", hashes.sha256());
        initBody.add("fileSize", String.valueOf(fileSize));
        initBody.add("chunkSize", String.valueOf(chunkSizeBytes));
        initBody.add("totalChunks", String.valueOf(totalChunks));
        InitMultipartUploadResponse initResponse = postMultipart(
                "/api/files/multipart/init",
                initBody,
                INIT_MULTIPART_RESPONSE,
                "初始化分片上传"
        );

        Path chunkDirectory = Files.createTempDirectory("study-agent-perf-chunks-");
        try {
            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                long offset = (long) chunkIndex * chunkSizeBytes;
                int currentChunkSize = (int) Math.min(chunkSizeBytes, fileSize - offset);
                Path chunkFile = copyChunkToTempFile(file.path(), chunkDirectory, chunkIndex, offset, currentChunkSize);
                try {
                    MultiValueMap<String, Object> chunkBody = new LinkedMultiValueMap<>();
                    chunkBody.add("chunk", namedFileResource(chunkFile, "chunk-" + chunkIndex + ".part"));
                    postMultipart(
                            "/api/files/multipart/" + initResponse.uploadSessionId() + "/chunks/" + chunkIndex,
                            chunkBody,
                            VOID_RESPONSE,
                            "上传分片 " + chunkIndex
                    );
                } finally {
                    Files.deleteIfExists(chunkFile);
                }
            }
        } finally {
            Files.deleteIfExists(chunkDirectory);
        }

        MultiValueMap<String, Object> completeBody = new LinkedMultiValueMap<>();
        completeBody.add("uploadSessionId", initResponse.uploadSessionId().toString());
        completeBody.add("knowledgeBaseId", knowledgeBaseId.toString());
        return postMultipart("/api/files/multipart/complete", completeBody, UPLOAD_RESPONSE, "完成分片上传");
    }

    /**
     * 构造一个“不发 MQ”的传统同步基线：对象先写 RustFS，随后写 file/document 业务表。
     *
     * <p>生产服务已经默认走 RocketMQ，测试中不应为了造基线去改生产分支，所以这里显式创建测试夹具。
     * 后续再调用 DocumentProcessingService.process，即可得到“请求线程一直等到 ES 索引完成”的传统总耗时。</p>
     */
    private UploadedDocument uploadFileAndCreateDocumentWithoutMq(Long knowledgeBaseId, BenchmarkFile file) throws Exception {
        long startedAt = System.nanoTime();
        FileHashes hashes = hashes(file.path());
        String objectKey = "perf/sync/" + hashes.md5() + "/" + file.filename();
        try (InputStream inputStream = Files.newInputStream(file.path())) {
            objectStorageService.putObject(objectKey, inputStream, file.size(), file.contentType());
        }

        LocalDateTime now = LocalDateTime.now();
        FileRecord fileRecord = new FileRecord();
        fileRecord.setUserId(KnowledgeBaseService.DEFAULT_USER_ID);
        fileRecord.setMd5(hashes.md5());
        fileRecord.setSha256(hashes.sha256());
        fileRecord.setBucket(objectStorageProperties.bucket());
        fileRecord.setObjectKey(objectKey);
        fileRecord.setFilename(file.filename());
        fileRecord.setContentType(file.contentType());
        fileRecord.setSize(file.size());
        fileRecord.setStorageProvider("RUSTFS_S3");
        fileRecord.setStatus("ACTIVE");
        fileRecord.setCreatedAt(now);
        fileRecord.setUpdatedAt(now);
        fileRecordMapper.insert(fileRecord);

        Document document = new Document();
        document.setUserId(KnowledgeBaseService.DEFAULT_USER_ID);
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setFileId(fileRecord.getId());
        document.setTitle(file.filename());
        document.setSourceType("UPLOAD");
        document.setParseStatus("UPLOADED");
        document.setIndexStatus("PENDING");
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentMapper.insert(document);
        return new UploadedDocument(fileRecord.getId(), document.getId(), elapsedSince(startedAt));
    }

    private <T> T postMultipart(
            String url,
            MultiValueMap<String, Object> body,
            ParameterizedTypeReference<ApiResponse<T>> responseType,
            String operation
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ApiResponse<T>> response = restTemplate.exchange(
                url,
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers),
                responseType
        );
        return requireOk(response, operation);
    }

    private <T> T requireOk(ResponseEntity<ApiResponse<T>> response, String operation) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new AssertionError(operation + " HTTP 请求失败: " + response.getStatusCode());
        }
        ApiResponse<T> body = response.getBody();
        if (body.code() != 0) {
            throw new AssertionError(operation + " 业务响应失败: " + body.message());
        }
        return body.data();
    }

    private DocumentState waitUntilIndexed(Long documentId, Duration timeout) throws InterruptedException {
        long startedAt = System.nanoTime();
        while (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(timeout) < 0) {
            Document document = documentMapper.selectById(documentId);
            if (document != null && "INDEXED".equals(document.getIndexStatus())) {
                return new DocumentState(document.getParseStatus(), document.getIndexStatus(), System.nanoTime());
            }
            if (document != null && "FAILED".equals(document.getIndexStatus())) {
                throw new AssertionError("文档索引失败: documentId=" + documentId + ", error=" + document.getErrorMessage());
            }
            Thread.sleep(500);
        }
        throw new AssertionError("等待文档索引超时: documentId=" + documentId + ", timeout=" + timeout);
    }

    private DocumentState requireIndexed(Long documentId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new AssertionError("文档不存在: " + documentId);
        }
        if (!"INDEXED".equals(document.getIndexStatus())) {
            throw new AssertionError("文档未完成索引: documentId=" + documentId
                    + ", parseStatus=" + document.getParseStatus()
                    + ", indexStatus=" + document.getIndexStatus()
                    + ", error=" + document.getErrorMessage());
        }
        return new DocumentState(document.getParseStatus(), document.getIndexStatus(), System.nanoTime());
    }

    private Path createTextFile(long targetBytes, String marker) throws Exception {
        Path file = Files.createTempFile("study-agent-upload-perf-", ".txt");
        try (OutputStream outputStream = Files.newOutputStream(file, StandardOpenOption.TRUNCATE_EXISTING)) {
            long written = 0;
            long lineNumber = 0;
            while (written < targetBytes) {
                byte[] line = ("StudyAgent upload performance sample "
                        + marker
                        + " line "
                        + lineNumber
                        + " RAG embedding Elasticsearch RocketMQ RustFS multipart upload benchmark.\n")
                        .getBytes(StandardCharsets.UTF_8);
                int length = (int) Math.min(line.length, targetBytes - written);
                outputStream.write(line, 0, length);
                written += length;
                lineNumber++;
            }
        }
        return file;
    }

    private BenchmarkFile benchmarkFile(
            String specificSourceProperty,
            String fallbackSourceProperty,
            String generatedSizeProperty,
            int defaultMegabytes,
            String marker
    ) throws Exception {
        String configuredSource = firstText(
                System.getProperty(specificSourceProperty),
                System.getProperty(fallbackSourceProperty),
                System.getProperty("studyagent.perf.source-file")
        );
        if (configuredSource != null) {
            return copySourceFile(Path.of(configuredSource), marker);
        }

        Path generated = createTextFile(megabytes(generatedSizeProperty, defaultMegabytes), marker);
        return new BenchmarkFile(
                generated,
                "perf-" + marker + ".txt",
                MediaType.TEXT_PLAIN_VALUE,
                Files.size(generated),
                true
        );
    }

    private BenchmarkFile copySourceFile(Path source, String marker) throws Exception {
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("性能测试源文件不存在或不是普通文件: " + source);
        }
        String extension = extension(source.getFileName().toString());
        Path copy = Files.createTempFile("study-agent-upload-perf-", extension);
        Files.copy(source, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        appendUniqueMarker(copy, marker);
        return new BenchmarkFile(
                copy,
                "perf-" + marker + extension,
                contentType(copy),
                Files.size(copy),
                true
        );
    }

    /**
     * 追加很小的唯一标记，避免测试多次上传同一文件时被 MD5/SHA256 去重变成“秒传”。
     *
     * <p>如果你要对不可追加的二进制格式做严格测试，可以通过
     * single-upload-source-file/multipart-upload-source-file 等参数分别传入两个已经不同的文件。</p>
     */
    private void appendUniqueMarker(Path copy, String marker) throws Exception {
        byte[] suffix = ("\n\nStudyAgent performance marker: " + marker + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(copy, suffix, StandardOpenOption.APPEND);
    }

    private Path copyChunkToTempFile(Path source, Path chunkDirectory, int chunkIndex, long offset, int length) throws Exception {
        Path chunkFile = chunkDirectory.resolve("chunk-" + chunkIndex + ".part");
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(chunkFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            input.position(offset);
            long remaining = length;
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = input.read(buffer);
                if (read == -1) {
                    break;
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                remaining -= read;
            }
        }
        return chunkFile;
    }

    private FileHashes hashes(Path file) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        try (InputStream inputStream = Files.newInputStream(file)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                md5.update(buffer, 0, read);
                sha256.update(buffer, 0, read);
            }
        }
        return new FileHashes(
                HexFormat.of().formatHex(md5.digest()),
                HexFormat.of().formatHex(sha256.digest())
        );
    }

    private FileSystemResource namedFileResource(Path file, String filename) {
        return new FileSystemResource(file) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private <T> TimedResult<T> timed(CheckedSupplier<T> supplier) throws Exception {
        long startedAt = System.nanoTime();
        T value = supplier.get();
        return new TimedResult<>(value, elapsedSince(startedAt));
    }

    private Duration elapsedSince(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    private int totalChunks(long fileSize, int chunkSizeBytes) {
        return Math.toIntExact((fileSize + chunkSizeBytes - 1) / chunkSizeBytes);
    }

    private int megabytes(String propertyName, int defaultMegabytes) {
        int megabytes = Integer.getInteger(propertyName, defaultMegabytes);
        if (megabytes <= 0) {
            throw new IllegalArgumentException(propertyName + " 必须大于 0");
        }
        return Math.multiplyExact(megabytes, 1024 * 1024);
    }

    private String contentType(Path file) throws Exception {
        String contentType = Files.probeContentType(file);
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        return contentType;
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".bin";
        }
        return filename.substring(dot);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String bytes(long bytes) {
        double megabytes = bytes / 1024.0 / 1024.0;
        return String.format(Locale.ROOT, "%.2f MB", megabytes);
    }

    private String duration(Duration duration) {
        return String.format(Locale.ROOT, "%.3f s (%d ms)", duration.toNanos() / 1_000_000_000.0, duration.toMillis());
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private record TimedResult<T>(
            T value,
            Duration duration
    ) {
    }

    private record FileHashes(
            String md5,
            String sha256
    ) {
    }

    private record UploadedDocument(
            Long fileId,
            Long documentId,
            Duration uploadAndDbDuration
    ) {
    }

    private record BenchmarkFile(
            Path path,
            String filename,
            String contentType,
            long size,
            boolean temporary
    ) {
        private void deleteIfTemporary() throws Exception {
            if (temporary) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record DocumentState(
            String parseStatus,
            String indexStatus,
            long observedAtNanos
    ) {
    }
}
