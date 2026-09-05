package com.studyagent.learning.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.learning.LearningTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning/traces")
@RequiredArgsConstructor
public class LearningTraceController {

    private final LearningTraceService traceService;
    private final CurrentUserContext currentUserContext;

    @GetMapping("/{traceId}")
    public ApiResponse<java.util.List<LearningSessionResponse.TraceEventResponse>> get(
            @PathVariable String traceId) {
        return ApiResponse.ok(traceService.find(currentUserContext.userId(), traceId).stream()
                .map(event -> new LearningSessionResponse.TraceEventResponse(
                        event.getSequenceNo(),
                        event.getStage(),
                        event.getEventType(),
                        event.getSummary(),
                        event.getStatus(),
                        event.getCreatedAt()))
                .toList());
    }
}
