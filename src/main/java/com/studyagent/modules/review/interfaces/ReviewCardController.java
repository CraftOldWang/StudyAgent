package com.studyagent.modules.review.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.review.application.ReviewCardService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review/cards")
@RequiredArgsConstructor
public class ReviewCardController {

    private final ReviewCardService reviewCardService;

    @PostMapping
    public ApiResponse<ReviewCardResponse> create(@Valid @RequestBody ReviewCardCreateRequest request) {
        return ApiResponse.ok(reviewCardService.create(request));
    }

    @GetMapping("/{cardId}")
    public ApiResponse<ReviewCardResponse> get(@PathVariable Long cardId) {
        return ApiResponse.ok(reviewCardService.get(cardId));
    }

    @GetMapping
    public ApiResponse<List<ReviewCardResponse>> list(@RequestParam(required = false) String status) {
        return ApiResponse.ok(reviewCardService.list(status));
    }

    @GetMapping("/due")
    public ApiResponse<List<ReviewCardResponse>> due(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(reviewCardService.due(limit));
    }

    @PatchMapping("/{cardId}")
    public ApiResponse<ReviewCardResponse> update(
            @PathVariable Long cardId,
            @RequestBody ReviewCardUpdateRequest request
    ) {
        return ApiResponse.ok(reviewCardService.update(cardId, request));
    }

    @DeleteMapping("/{cardId}")
    public ApiResponse<Void> delete(@PathVariable Long cardId) {
        reviewCardService.delete(cardId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{cardId}/reviews")
    public ApiResponse<ReviewSubmitResponse> submitReview(
            @PathVariable Long cardId,
            @Valid @RequestBody SubmitReviewRequest request
    ) {
        return ApiResponse.ok(reviewCardService.submitReview(cardId, request.rating(), request.reviewedAt()));
    }
}
