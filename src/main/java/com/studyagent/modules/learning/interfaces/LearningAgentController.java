package com.studyagent.modules.learning.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.learning.application.LearningAgentEvent;
import com.studyagent.modules.learning.application.LearningAgentService;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningAgentController {

    private final LearningAgentService learningAgentService;

    @PostMapping("/sessions")
    public ApiResponse<LearningSessionResponse> createSession(@Valid @RequestBody LearningSessionRequest request) {
        return ApiResponse.ok(learningAgentService.createSession(request.message(), request.knowledgeBaseIds()));
    }

    @PostMapping(value = "/sessions/{sessionId}/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgent(
            @PathVariable Long sessionId,
            @Valid @RequestBody LearningChatRequest request
    ) {
        SseEmitter emitter = new SseEmitter(0L);
        Thread.startVirtualThread(() -> {
            try {
                learningAgentService.runSession(sessionId, request.message(), event -> send(emitter, event));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, LearningAgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.event())
                    .data(event.data(), MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            throw new IllegalStateException("发送 SSE 事件失败: " + ex.getMessage(), ex);
        }
    }
}
