package com.studyagent.learning.web;

import com.studyagent.agent.integration.ModelCallScope;
import com.studyagent.config.LearningConversationProperties;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.identity.IdentityScope;
import com.studyagent.learning.LearningConversationService;
import com.studyagent.learning.LearningPersistenceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/learning/sessions")
public class LearningStreamController {
    private final CurrentUserContext user;
    private final IdentityScope identity;
    private final LearningConversationService conversation;
    private final LearningPersistenceService learning;
    private final LearningResponseAssembler assembler;
    private final LearningConversationProperties properties;
    private final ExecutorService executor;

    public LearningStreamController(CurrentUserContext user, IdentityScope identity, LearningConversationService conversation,
            LearningPersistenceService learning, LearningResponseAssembler assembler, LearningConversationProperties properties,
            @Qualifier("learningConversationExecutor") ExecutorService executor) {
        this.user = user; this.identity = identity; this.conversation = conversation; this.learning = learning;
        this.assembler = assembler; this.properties = properties; this.executor = executor;
    }

    @PostMapping(value = "/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long sessionId, @Valid @RequestBody Request request) {
        Long userId = user.userId();
        learning.requireSession(userId, sessionId);
        SseEmitter emitter = new SseEmitter(properties.leaseSeconds() * 1000L);
        Channel channel = new Channel(emitter);
        ModelCallScope caller = ModelCallScope.current();
        emitter.onCompletion(channel::disconnect);
        emitter.onTimeout(channel::disconnect);
        emitter.onError(error -> channel.disconnect());
        executor.submit(() -> {
            try (var ignored = identity.bind(userId)) {
                if (caller != null) { ModelCallScope.bind(caller); }
                channel.send("accepted", Map.of("requestId", request.requestId()));
                var turn = conversation.message(userId, sessionId, request.requestId(), request.message(),
                        event -> channel.send(event.type(), Map.of("text", event.text())));
                channel.send("result", new LearningTurnResponse(turn.getTraceId(), turn.getAssistantMessage(), assembler.session(userId, sessionId), turn));
            } catch (RuntimeException error) {
                channel.send("failure", Map.of("requestId", request.requestId(), "message",
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            } finally {
                ModelCallScope.clear();
                channel.complete();
            }
        });
        return emitter;
    }

    public record Request(@NotBlank String message, @NotBlank @Size(max = 64) String requestId) { }

    // Transport disconnection does not cancel the business turn; clients query its persisted status.
    static final class Channel {
        private final SseEmitter emitter;
        private final AtomicBoolean connected = new AtomicBoolean(true);
        Channel(SseEmitter emitter) { this.emitter = emitter; }
        synchronized void send(String name, Object value) {
            if (!connected.get()) { return; }
            try { emitter.send(SseEmitter.event().name(name).data(value, MediaType.APPLICATION_JSON)); }
            catch (IOException | IllegalStateException disconnected) { disconnect(); }
        }
        void disconnect() { connected.set(false); }
        void complete() { if (connected.getAndSet(false)) { emitter.complete(); } }
    }
}
