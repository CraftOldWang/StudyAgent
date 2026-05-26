package com.studyagent.modules.storage.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.storage.application.FileUploadService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
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

    @PostMapping("/multipart/complete")
    public ApiResponse<UploadResultResponse> completeMultipart(
            @Valid @ModelAttribute CompleteMultipartUploadRequest request) {
        return ApiResponse.ok(fileUploadService.completeMultipart(request.uploadSessionId(), request.knowledgeBaseId()));
    }
}
