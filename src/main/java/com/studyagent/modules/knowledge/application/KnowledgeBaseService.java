package com.studyagent.modules.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.modules.knowledge.domain.KnowledgeBase;
import com.studyagent.modules.knowledge.infrastructure.KnowledgeBaseMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    public static final Long DEFAULT_USER_ID = 1L;

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBase getOrCreateDefault(Long userId) {
        KnowledgeBase existing = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getName, "默认知识库")
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setUserId(userId);
        knowledgeBase.setName("默认知识库");
        knowledgeBase.setDescription("用于本地验证的默认知识库");
        knowledgeBase.setStatus("ACTIVE");
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase;
    }
}
