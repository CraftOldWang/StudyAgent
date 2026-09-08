package com.studyagent.learning.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LearningStreamControllerTest {
    @Test
    void transportFailureStopsDeliveryWithoutThrowingIntoTheLearningTurn() throws Exception {
        var emitter = mock(SseEmitter.class);
        doThrow(new IOException("client disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        var channel = new LearningStreamController.Channel(emitter);
        assertThatCode(() -> { channel.send("text", "part"); channel.send("result", "saved"); channel.complete(); }).doesNotThrowAnyException();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
    }
}
