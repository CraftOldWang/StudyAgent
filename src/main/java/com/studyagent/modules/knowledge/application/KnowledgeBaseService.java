package com.studyagent.modules.knowledge.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.domain.KnowledgeBase;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import com.studyagent.modules.knowledge.infrastructure.KnowledgeBaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    public static final Long DEFAULT_USER_ID = 1L;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;

    @Transactional
    public KnowledgeBase getOrCreateDefault(Long userId) {
        KnowledgeBase existing = knowledgeBaseMapper.selectOne(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .eq(KnowledgeBase::getName, "默认知识库")
                .ne(KnowledgeBase::getStatus, "DELETED")
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

    public List<KnowledgeBase> list(Long userId) {
        getOrCreateDefault(userId);
        return knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getUserId, userId)
                .ne(KnowledgeBase::getStatus, "DELETED")
                .orderByDesc(KnowledgeBase::getUpdatedAt));
    }

    @Transactional
    public KnowledgeBase create(Long userId, String name, String description) {
        validateName(name);
        LocalDateTime now = LocalDateTime.now();
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setUserId(userId);
        knowledgeBase.setName(name.trim());
        knowledgeBase.setDescription(normalizeDescription(description));
        knowledgeBase.setStatus("ACTIVE");
        knowledgeBase.setCreatedAt(now);
        knowledgeBase.setUpdatedAt(now);
        knowledgeBaseMapper.insert(knowledgeBase);
        return knowledgeBase;
    }

    public KnowledgeBase get(Long userId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseMapper.selectById(knowledgeBaseId);
        if (knowledgeBase == null
                || !userId.equals(knowledgeBase.getUserId())
                || "DELETED".equals(knowledgeBase.getStatus())) {
            throw new BusinessException("知识库不存在或无权访问");
        }
        return knowledgeBase;
    }

    @Transactional
    public KnowledgeBase update(Long userId, Long knowledgeBaseId, String name, String description, String status) {
        KnowledgeBase knowledgeBase = get(userId, knowledgeBaseId);
        if (name != null) {
            validateName(name);
            knowledgeBase.setName(name.trim());
        }
        if (description != null) {
            knowledgeBase.setDescription(normalizeDescription(description));
        }
        if (status != null && !status.isBlank()) {
            knowledgeBase.setStatus(normalizeStatus(status));
        }
        knowledgeBase.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.updateById(knowledgeBase);
        return knowledgeBase;
    }

    @Transactional
    public void delete(Long userId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = get(userId, knowledgeBaseId);
        knowledgeBase.setStatus("DELETED");
        knowledgeBase.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseMapper.updateById(knowledgeBase);
    }

    public List<Document> listDocuments(Long userId, Long knowledgeBaseId) {
        get(userId, knowledgeBaseId);
        return documentMapper.selectList(new LambdaQueryWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getKnowledgeBaseId, knowledgeBaseId)
                .orderByDesc(Document::getCreatedAt));
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("知识库名称不能为空");
        }
        if (name.trim().length() > 128) {
            throw new BusinessException("知识库名称不能超过 128 个字符");
        }
    }

    private String normalizeDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String normalized = description.trim();
        if (normalized.length() > 512) {
            throw new BusinessException("知识库描述不能超过 512 个字符");
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        String normalized = status.trim().toUpperCase();
        if (!List.of("ACTIVE", "ARCHIVED").contains(normalized)) {
            throw new BusinessException("知识库状态必须是 ACTIVE 或 ARCHIVED");
        }
        return normalized;
    }
}
