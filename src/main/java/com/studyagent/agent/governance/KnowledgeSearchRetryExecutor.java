package com.studyagent.agent.governance;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 只为 knowledge_search 的明确超时失败提供有限重试。
 */
@Component
public final class KnowledgeSearchRetryExecutor {

    static final String KNOWLEDGE_SEARCH = "knowledge_search";
    static final int MAX_RETRIES = 3;

    public <T> T execute(String toolName, Supplier<T> operation) {
        int retries = 0;
        while (true) {
            try {
                return operation.get();
            } catch (RuntimeException ex) {
                if (!KNOWLEDGE_SEARCH.equals(toolName)
                        || !isTimeout(ex)
                        || retries >= MAX_RETRIES) {
                    throw ex;
                }
                retries++;
            }
        }
    }

    private boolean isTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
