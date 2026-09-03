package com.studyagent.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.ReviewCardMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.ReviewCard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V3 复习卡写入服务，负责来源归属校验和单事务批量持久化。
 */
@Service
@RequiredArgsConstructor
public class ReviewCardService {

    private final ReviewCardMapper reviewCardMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentMapper documentMapper;

    @Transactional
    public List<ReviewCard> writeBatch(
            Long userId,
            Long knowledgePointId,
            Long knowledgeBaseId,
            List<Draft> drafts
    ) {
        validateScope(userId, knowledgePointId, knowledgeBaseId);
        if (drafts == null || drafts.isEmpty()) {
            throw new BusinessException("复习卡草稿不能为空");
        }

        List<ReviewCard> cards = drafts.stream()
                .map(draft -> toReviewCard(userId, knowledgePointId, knowledgeBaseId, draft))
                .toList();
        cards.forEach(reviewCardMapper::insert);
        return cards;
    }

    private ReviewCard toReviewCard(
            Long userId,
            Long knowledgePointId,
            Long knowledgeBaseId,
            Draft draft
    ) {
        if (draft == null) {
            throw new BusinessException("复习卡草稿格式错误");
        }
        validateText(draft.front(), "复习卡正面不能为空");
        validateText(draft.back(), "复习卡背面不能为空");
        validateText(draft.sourceChunkId(), "复习卡来源 chunk 不能为空");

        DocumentChunk chunk = documentChunkMapper.selectOne(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getChunkId, draft.sourceChunkId()));
        if (chunk == null || chunk.getDocumentId() == null) {
            throw new BusinessException("复习卡来源 chunk 不存在: " + draft.sourceChunkId());
        }
        Document document = documentMapper.selectById(chunk.getDocumentId());
        if (document == null
                || !Objects.equals(userId, document.getUserId())
                || !Objects.equals(knowledgeBaseId, document.getKnowledgeBaseId())) {
            throw new BusinessException("复习卡来源 chunk 不属于当前用户和知识库: " + draft.sourceChunkId());
        }

        ReviewCard card = new ReviewCard();
        card.setUserId(userId);
        card.setKnowledgePointId(knowledgePointId);
        card.setKnowledgeBaseId(knowledgeBaseId);
        card.setFront(draft.front());
        card.setBack(draft.back());
        card.setSourceChunkId(draft.sourceChunkId());
        card.setExportedToAnki(false);
        card.setCreatedAt(LocalDateTime.now());
        return card;
    }

    private void validateScope(Long userId, Long knowledgePointId, Long knowledgeBaseId) {
        if (userId == null) {
            throw new BusinessException("userId 不能为空");
        }
        if (knowledgePointId == null) {
            throw new BusinessException("knowledgePointId 不能为空");
        }
        if (knowledgeBaseId == null) {
            throw new BusinessException("knowledgeBaseId 不能为空");
        }
    }

    private void validateText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
    }

    public record Draft(
            String front,
            String back,
            String sourceChunkId
    ) {
    }
}
