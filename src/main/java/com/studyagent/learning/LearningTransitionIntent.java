package com.studyagent.learning;

import com.studyagent.common.exception.BusinessException;

public final class LearningTransitionIntent {
    private final KnowledgePointStatus current;
    private final KnowledgePointStatus expected;
    private KnowledgePointStatus requested;

    public LearningTransitionIntent(KnowledgePointStatus current, KnowledgePointStatus expected) {
        this.current = current;
        this.expected = expected;
    }

    public synchronized void request(KnowledgePointStatus target) {
        if (expected == null) {
            throw new BusinessException("当前 Agent turn 不允许推进知识点状态");
        }
        KnowledgePointStatus validated = new KnowledgePointLifecycle().advance(current, target);
        if (validated != expected) {
            throw new BusinessException("当前 Agent turn 只允许推进到 " + expected);
        }
        if (requested != null) {
            throw new BusinessException("同一 Agent turn 只能请求一次状态推进");
        }
        requested = target;
    }

    public synchronized void requireRequested() {
        if (expected != null && requested != expected) {
            throw new BusinessException("Agent 未通过服务端工具请求状态推进到 " + expected);
        }
        if (expected == null && requested != null) {
            throw new BusinessException("答疑 turn 不允许改变知识点状态");
        }
    }
}
