package com.studyagent.modules.learning.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.learning.application.LearningAgentEvent;
import com.studyagent.modules.learning.application.LearningAgentV2Service;
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
 * Todo 驱动 Agent v2 接口。
 *
 * <p>这个 Controller 使用独立路径，不影响旧版固定阶段 Agent。等 v2 的工具调用和前端展示稳定后，
 * 可以再把旧端点下线或迁移到这里。</p>
 */
@RestController
@RequestMapping("/api/learning/v2")
@RequiredArgsConstructor
public class LearningAgentV2Controller {

    private final LearningAgentV2Service learningAgentV2Service;

    /**
     * 创建 Todo 驱动的新学习会话。
     */
    @PostMapping("/sessions")
    public ApiResponse<LearningSessionResponse> createSession(@Valid @RequestBody LearningSessionRequest request) {
        return ApiResponse.ok(learningAgentV2Service.createSession(request.message(), request.knowledgeBaseIds()));
    }

    /**
     * 通过 SSE 执行一次 Agent v2 交互。
     */
    @PostMapping(value = "/sessions/{sessionId}/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAgent(
            @PathVariable Long sessionId,
            @Valid @RequestBody LearningChatRequest request
    ) {
        SseEmitter emitter = new SseEmitter(0L);
        Thread.startVirtualThread(() -> {
            try {
                learningAgentV2Service.runSession(sessionId, request.message(), event -> send(emitter, event));
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
