package com.studyagent.modules.storage.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.storage.application.FileUploadPerformanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 性能测试接口层。
 *
 * <p>这些接口会真实写入 RustFS、MySQL、RocketMQ、Elasticsearch，并调用当前配置的真实 embedding provider。
 * 因此它们用于本地验证和演示，不建议暴露到公网或生产环境。</p>
 */
@Validated
@RestController
@RequestMapping("/api/performance/files")
@Slf4j
@RequiredArgsConstructor
public class FileUploadPerformanceController {

    private final FileUploadPerformanceService performanceService;

    /**
     * 比较同一文件直传 RustFS 与 S3 原生 Multipart Upload 的耗时。
     *
     * <p>保留这个接口用于后端侧对比：浏览器仍会先把整个文件传给后端，然后后端再分别写 RustFS。
     * 前端真实并发分片测试使用下面的 direct/browser-multipart 三段式接口。</p>
     */
    @PostMapping("/upload-comparison")
    public ApiResponse<PerformanceUploadComparisonResponse> compareUpload(
            @RequestParam @NotNull Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "8388608") @Min(1) int chunkSizeBytes,
            @RequestParam(defaultValue = "4") @Min(1) int chunkConcurrency,
            @RequestParam(defaultValue = "false") boolean triggerIndex
    ) {
        long startedAt = System.nanoTime();
        log.info(
                "收到上传性能对比请求: endpoint=/api/performance/files/upload-comparison, method=POST, knowledgeBaseId={}, filename={}, size={}, chunkSizeBytes={}, chunkConcurrency={}, triggerIndex={}",
                knowledgeBaseId,
                file.getOriginalFilename(),
                file.getSize(),
                chunkSizeBytes,
                chunkConcurrency,
                triggerIndex
        );
        PerformanceUploadComparisonResponse response =
                performanceService.compareUpload(knowledgeBaseId, file, chunkSizeBytes, chunkConcurrency, triggerIndex);
        log.info(
                "上传性能对比请求完成: filename={}, directTotalMillis={}, multipartTotalMillis={}, totalMillis={}",
                response.filename(),
                response.direct().totalMillis(),
                response.multipart().totalMillis(),
                elapsedMillis(startedAt)
        );
        return ApiResponse.ok(response);
    }

    /**
     * 前端单请求直传：浏览器一次 POST 整个文件，后端收到后写入 RustFS。
     */
    @PostMapping("/upload-comparison/direct")
    public ApiResponse<PerformanceDirectUploadResponse> directUploadFromBrowser(
            @RequestParam @NotNull Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean triggerIndex
    ) {
        long startedAt = System.nanoTime();
        log.info(
                "收到前端单请求直传性能测试: knowledgeBaseId={}, filename={}, size={}, triggerIndex={}",
                knowledgeBaseId,
                file.getOriginalFilename(),
                file.getSize(),
                triggerIndex
        );
        PerformanceDirectUploadResponse response =
                performanceService.uploadDirectFromBrowser(knowledgeBaseId, file, triggerIndex);
        log.info(
                "前端单请求直传性能测试完成: filename={}, backendTotalMillis={}, requestVisibleMillis={}",
                response.filename(),
                response.direct().totalMillis(),
                elapsedMillis(startedAt)
        );
        return ApiResponse.ok(response);
    }

    /**
     * 初始化前端并发分片上传，返回 S3 uploadId 和目标 objectKey。
     */
    @PostMapping("/upload-comparison/multipart/init")
    public ApiResponse<PerformanceMultipartInitResponse> initBrowserMultipart(
            @RequestParam String filename,
            @RequestParam(required = false) String contentType,
            @RequestParam @Min(1) long fileSize,
            @RequestParam(defaultValue = "8388608") @Min(1) int chunkSizeBytes
    ) {
        return ApiResponse.ok(performanceService.initBrowserMultipart(
                filename,
                contentType,
                fileSize,
                chunkSizeBytes
        ));
    }

    /**
     * 接收前端单个 chunk，并直接写入 RustFS/S3 Multipart part。
     */
    @PostMapping("/upload-comparison/multipart/parts/{partNumber}")
    public ApiResponse<PerformanceMultipartPartResponse> uploadBrowserMultipartPart(
            @PathVariable @Min(1) int partNumber,
            @RequestParam String objectKey,
            @RequestParam String uploadId,
            @RequestParam("file") MultipartFile chunk
    ) {
        return ApiResponse.ok(performanceService.uploadBrowserMultipartPart(
                objectKey,
                uploadId,
                partNumber,
                chunk
        ));
    }

    /**
     * 完成前端并发分片上传，由 RustFS/S3 complete multipart 并创建业务记录。
     */
    @PostMapping("/upload-comparison/multipart/complete")
    public ApiResponse<PerformanceMultipartCompleteResponse> completeBrowserMultipart(
            @RequestBody @Valid PerformanceMultipartCompleteRequest request
    ) {
        return ApiResponse.ok(performanceService.completeBrowserMultipart(request));
    }

    /**
     * 前端分片测试失败或取消时，中止 RustFS/S3 Multipart Upload。
     */
    @DeleteMapping("/upload-comparison/multipart")
    public ApiResponse<Void> abortBrowserMultipart(
            @RequestParam String objectKey,
            @RequestParam String uploadId
    ) {
        performanceService.abortBrowserMultipart(objectKey, uploadId);
        return ApiResponse.ok(null);
    }

    /**
     * 比较同步处理链路与 RocketMQ 解耦链路的用户等待时间和最终索引完成时间。
     */
    @PostMapping("/pipeline-comparison")
    public ApiResponse<PerformancePipelineComparisonResponse> comparePipeline(
            @RequestParam @NotNull Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "300") @Min(1) long waitTimeoutSeconds
    ) {
        long startedAt = System.nanoTime();
        log.info(
                "收到处理链路性能对比请求: endpoint=/api/performance/files/pipeline-comparison, method=POST, knowledgeBaseId={}, filename={}, size={}, waitTimeoutSeconds={}",
                knowledgeBaseId,
                file.getOriginalFilename(),
                file.getSize(),
                waitTimeoutSeconds
        );
        PerformancePipelineComparisonResponse response = performanceService.comparePipeline(
                knowledgeBaseId,
                file,
                Duration.ofSeconds(waitTimeoutSeconds)
        );
        log.info(
                "处理链路性能对比请求完成: filename={}, syncResponseMillis={}, syncIndexedMillis={}, rocketMqResponseMillis={}, rocketMqIndexedMillis={}, totalMillis={}",
                response.filename(),
                response.synchronous().responseMillis(),
                response.synchronous().indexedMillis(),
                response.rocketMq().responseMillis(),
                response.rocketMq().indexedMillis(),
                elapsedMillis(startedAt)
        );
        return ApiResponse.ok(response);
    }

    private long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
