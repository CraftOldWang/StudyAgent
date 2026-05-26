package com.studyagent.modules.review.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.review.domain.CardState;
import com.studyagent.modules.review.domain.FsrsCardState;
import com.studyagent.modules.review.domain.FsrsScheduler;
import com.studyagent.modules.review.domain.FsrsSchedulingResult;
import com.studyagent.modules.review.domain.ReviewCard;
import com.studyagent.modules.review.domain.ReviewRating;
import com.studyagent.modules.review.domain.ReviewRecord;
import com.studyagent.modules.review.infrastructure.ReviewCardMapper;
import com.studyagent.modules.review.infrastructure.ReviewRecordMapper;
import com.studyagent.modules.review.interfaces.ReviewCardCreateRequest;
import com.studyagent.modules.review.interfaces.ReviewCardResponse;
import com.studyagent.modules.review.interfaces.ReviewCardUpdateRequest;
import com.studyagent.modules.review.interfaces.ReviewRecordResponse;
import com.studyagent.modules.review.interfaces.ReviewSubmitResponse;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewCardService {

    private static final Long DEFAULT_USER_ID = KnowledgeBaseService.DEFAULT_USER_ID;

    private final ReviewCardMapper reviewCardMapper;
    private final ReviewRecordMapper reviewRecordMapper;
    private final ObjectMapper objectMapper;
    private final FsrsScheduler fsrsScheduler = new FsrsScheduler();

    @Transactional
    public ReviewCardResponse create(ReviewCardCreateRequest request) {
        validateCardText(request.front(), request.back());
        LocalDateTime now = LocalDateTime.now();
        FsrsCardState initialState = fsrsScheduler.newCard(now);

        ReviewCard card = new ReviewCard();
        card.setUserId(DEFAULT_USER_ID);
        card.setKnowledgeBaseId(request.knowledgeBaseId());
        card.setDocumentId(request.documentId());
        card.setSessionId(request.sessionId());
        card.setFront(request.front());
        card.setBack(request.back());
        card.setTagsJson(toJson(request.tags() == null ? List.of() : request.tags()));
        card.setSourceMessageId(request.sourceMessageId());
        card.setSourceChunkIdsJson(toJson(request.sourceChunkIds() == null ? List.of() : request.sourceChunkIds()));
        card.setStatus("ACTIVE");
        applyState(card, initialState);
        card.setCreatedAt(now);
        card.setUpdatedAt(now);
        reviewCardMapper.insert(card);
        return ReviewCardResponse.from(card);
    }

    public ReviewCardResponse get(Long cardId) {
        return ReviewCardResponse.from(requireCard(cardId));
    }

    public List<ReviewCardResponse> list(String status) {
        LambdaQueryWrapper<ReviewCard> wrapper = new LambdaQueryWrapper<ReviewCard>()
                .eq(ReviewCard::getUserId, DEFAULT_USER_ID)
                .orderByAsc(ReviewCard::getDueAt);
        if (status != null && !status.isBlank()) {
            wrapper.eq(ReviewCard::getStatus, status.trim().toUpperCase());
        }
        return reviewCardMapper.selectList(wrapper).stream()
                .map(ReviewCardResponse::from)
                .toList();
    }

    public List<ReviewCardResponse> due(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return reviewCardMapper.selectDueCards(DEFAULT_USER_ID, LocalDateTime.now(), safeLimit).stream()
                .map(ReviewCardResponse::from)
                .toList();
    }

    @Transactional
    public ReviewCardResponse update(Long cardId, ReviewCardUpdateRequest request) {
        ReviewCard card = requireCard(cardId);
        if (request.front() != null && !request.front().isBlank()) {
            card.setFront(request.front());
        }
        if (request.back() != null && !request.back().isBlank()) {
            card.setBack(request.back());
        }
        if (request.tags() != null) {
            card.setTagsJson(toJson(request.tags()));
        }
        if (request.status() != null && !request.status().isBlank()) {
            card.setStatus(normalizeStatus(request.status()));
        }
        card.setUpdatedAt(LocalDateTime.now());
        reviewCardMapper.updateById(card);
        return ReviewCardResponse.from(card);
    }

    @Transactional
    public void delete(Long cardId) {
        ReviewCard card = requireCard(cardId);
        card.setStatus("DELETED");
        card.setUpdatedAt(LocalDateTime.now());
        reviewCardMapper.updateById(card);
    }

    @Transactional
    public ReviewSubmitResponse submitReview(Long cardId, String ratingValue, LocalDateTime reviewedAt) {
        ReviewCard card = requireCard(cardId);
        if (!"ACTIVE".equals(card.getStatus())) {
            throw new BusinessException("只有 ACTIVE 状态的复习卡可以提交复习结果");
        }
        ReviewRating rating = ReviewRating.from(ratingValue);
        LocalDateTime actualReviewedAt = reviewedAt == null ? LocalDateTime.now() : reviewedAt;
        FsrsCardState before = toFsrsState(card);
        FsrsSchedulingResult result = fsrsScheduler.schedule(before, rating, actualReviewedAt);
        applyState(card, result.after());
        card.setUpdatedAt(LocalDateTime.now());
        reviewCardMapper.updateById(card);

        ReviewRecord record = createReviewRecord(card, result, actualReviewedAt);
        reviewRecordMapper.insert(record);
        return new ReviewSubmitResponse(ReviewCardResponse.from(card), ReviewRecordResponse.from(record));
    }

    private ReviewRecord createReviewRecord(ReviewCard card, FsrsSchedulingResult result, LocalDateTime reviewedAt) {
        ReviewRecord record = new ReviewRecord();
        record.setCardId(card.getId());
        record.setUserId(card.getUserId());
        record.setRating(result.rating().name());
        record.setReviewedAt(reviewedAt);
        record.setElapsedDays(result.after().elapsedDays());
        record.setScheduledDaysBefore(result.before().scheduledDays());
        record.setScheduledDaysAfter(result.after().scheduledDays());
        record.setStabilityBefore(result.before().stability());
        record.setStabilityAfter(result.after().stability());
        record.setDifficultyBefore(result.before().difficulty());
        record.setDifficultyAfter(result.after().difficulty());
        record.setStateBefore(result.before().state().name());
        record.setStateAfter(result.after().state().name());
        record.setDueAtBefore(result.before().dueAt());
        record.setDueAtAfter(result.after().dueAt());
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    private ReviewCard requireCard(Long cardId) {
        ReviewCard card = reviewCardMapper.selectById(cardId);
        if (card == null || !DEFAULT_USER_ID.equals(card.getUserId())) {
            throw new BusinessException("复习卡不存在或无权访问");
        }
        return card;
    }

    private FsrsCardState toFsrsState(ReviewCard card) {
        return new FsrsCardState(
                CardState.valueOf(card.getCardState()),
                card.getDueAt(),
                card.getLastReviewedAt(),
                card.getStability(),
                card.getDifficulty(),
                card.getElapsedDays(),
                card.getScheduledDays(),
                card.getReps(),
                card.getLapses()
        );
    }

    private void applyState(ReviewCard card, FsrsCardState state) {
        card.setCardState(state.state().name());
        card.setDueAt(state.dueAt());
        card.setLastReviewedAt(state.lastReviewedAt());
        card.setStability(state.stability());
        card.setDifficulty(state.difficulty());
        card.setElapsedDays(state.elapsedDays());
        card.setScheduledDays(state.scheduledDays());
        card.setReps(state.reps());
        card.setLapses(state.lapses());
    }

    private void validateCardText(String front, String back) {
        if (front == null || front.isBlank()) {
            throw new BusinessException("复习卡正面不能为空");
        }
        if (back == null || back.isBlank()) {
            throw new BusinessException("复习卡背面不能为空");
        }
    }

    private String normalizeStatus(String status) {
        String normalized = status.trim().toUpperCase();
        if (!List.of("ACTIVE", "SUSPENDED", "DELETED").contains(normalized)) {
            throw new BusinessException("复习卡状态必须是 ACTIVE、SUSPENDED 或 DELETED");
        }
        return normalized;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("JSON 序列化失败: " + ex.getMessage());
        }
    }
}
