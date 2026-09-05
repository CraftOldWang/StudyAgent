package com.studyagent.identity;

import com.studyagent.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 让 HTTP 与异步文档处理使用同一服务端身份来源，避免后台线程依赖 Servlet request scope。
 */
@Component
public final class IdentityScope {

    private final ThreadLocal<Long> currentUserId = new ThreadLocal<>();

    public Binding bind(Long userId) {
        if (userId == null) {
            throw new BusinessException("服务端身份不能为空");
        }
        Long previous = currentUserId.get();
        currentUserId.set(userId);
        return new Binding(previous);
    }

    public Long requireUserId() {
        Long userId = currentUserId.get();
        if (userId == null) {
            throw new BusinessException("当前执行线程缺少服务端身份 scope");
        }
        return userId;
    }

    public final class Binding implements AutoCloseable {
        private final Long previous;
        private boolean closed;

        private Binding(Long previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                currentUserId.remove();
            } else {
                currentUserId.set(previous);
            }
        }
    }
}
