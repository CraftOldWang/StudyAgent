package com.studyagent.ingest.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.ingest.pipeline.DocumentRetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentPipelineController {
    private final DocumentRetryService retryService;
    private final CurrentUserContext currentUserContext;

    @PostMapping("/{documentId}/retry")
    public ApiResponse<RetryResponse> retry(@PathVariable Long documentId) {
        retryService.retry(currentUserContext.userId(), documentId);
        return ApiResponse.ok(new RetryResponse(documentId, true));
    }

    public record RetryResponse(Long documentId, boolean retryRequested) {}
}
