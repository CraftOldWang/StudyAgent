package com.studyagent.agent.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KnowledgeSearchRetryExecutorTest {

    private final KnowledgeSearchRetryExecutor executor = new KnowledgeSearchRetryExecutor();

    @Test
    void retriesKnowledgeSearchTimeoutUntilItSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute("knowledge_search", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw timeoutFailure();
            }
            return "found";
        });

        assertThat(result).isEqualTo("found");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void stopsAfterThreeRetriesWhenKnowledgeSearchKeepsTimingOut() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("knowledge_search", () -> {
                    attempts.incrementAndGet();
                    throw timeoutFailure();
                }))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(TimeoutException.class);

        assertThat(attempts).hasValue(4);
    }

    @Test
    void doesNotRetryKnowledgeSearchNonTimeoutFailure() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("knowledge_search", () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("retrieval failed");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("retrieval failed");

        assertThat(attempts).hasValue(1);
    }

    @Test
    void doesNotRetryTimeoutFromAnotherTool() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute("review_card_write", () -> {
                    attempts.incrementAndGet();
                    throw new CompletionException(new SocketTimeoutException("timed out"));
                }))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);

        assertThat(attempts).hasValue(1);
    }

    private CompletionException timeoutFailure() {
        return new CompletionException(new TimeoutException("timed out"));
    }
}
