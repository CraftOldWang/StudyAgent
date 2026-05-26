package com.studyagent.modules.storage.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.storage.application.FileUploadService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileUploadService fileUploadService;

    @GetMapping("/dedup")
    public ApiResponse<FileDedupCheckResponse> checkDuplicate(
            @RequestParam String md5,
            @RequestParam(required = false) String sha256) {
        return ApiResponse.ok(fileUploadService.checkDuplicate(md5, sha256));
    }

    @PostMapping("/upload")
    public ApiResponse<UploadResultResponse> uploadSingle(
            @RequestParam @NotNull Long knowledgeBaseId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(fileUploadService.uploadSingle(knowledgeBaseId, file));
    }

    @PostMapping("/multipart/init")
    public ApiResponse<InitMultipartUploadResponse> initMultipart(
            @Valid @ModelAttribute InitMultipartUploadRequest request) {
        return ApiResponse.ok(fileUploadService.initMultipart(request));
    }

    @PostMapping("/multipart/{uploadSessionId}/chunks/{chunkIndex}")
    public ApiResponse<Void> uploadChunk(
            @PathVariable Long uploadSessionId,
            @PathVariable @Min(0) int chunkIndex,
            @RequestParam("chunk") MultipartFile chunk) {
        fileUploadService.uploadChunk(uploadSessionId, chunkIndex, chunk);
        return ApiResponse.ok(null);
    }

    @GetMapping("/multipart/{uploadSessionId}")
    public ApiResponse<MultipartUploadStatusResponse> multipartStatus(@PathVariable Long uploadSessionId) {
        return ApiResponse.ok(fileUploadService.multipartStatus(uploadSessionId));
    }

    @PostMapping("/multipart/complete")
    public ApiResponse<UploadResultResponse> completeMultipart(
            @Valid @ModelAttribute CompleteMultipartUploadRequest request) {
        return ApiResponse.ok(fileUploadService.completeMultipart(request.uploadSessionId(), request.knowledgeBaseId()));
    }
}
