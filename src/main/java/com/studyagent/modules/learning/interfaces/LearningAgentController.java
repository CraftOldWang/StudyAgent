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

/**
 * 学习 Agent 接口层，提供会话创建和 SSE 流式执行入口。
 */
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningAgentController {

    private final LearningAgentService learningAgentService;

    /**
     * 创建学习会话。
     */
    @PostMapping("/sessions")
    public ApiResponse<LearningSessionResponse> createSession(@Valid @RequestBody LearningSessionRequest request) {
        return ApiResponse.ok(learningAgentService.createSession(request.message(), request.knowledgeBaseIds()));
    }

    /**
     * 通过 SSE 推进学习 Agent，并持续返回阶段、工具和内容事件。
     */
    @PostMapping(value = "/sessions/{sessionId}/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgent(
            @PathVariable Long sessionId,
            @Valid @RequestBody LearningChatRequest request
    ) {
        SseEmitter emitter = new SseEmitter(0L);
        Thread.startVirtualThread(() -> {
            try {
                // 使用虚拟线程承载长连接，避免阻塞普通请求处理线程。
                learningAgentService.runSession(sessionId, request.message(), event -> send(emitter, event));
                emitter.complete();
            } catch (Exception ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    /**
     * 发送结构化 SSE 事件。
     */
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
