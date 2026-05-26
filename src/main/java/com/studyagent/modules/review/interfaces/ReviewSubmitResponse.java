package com.studyagent.modules.review.interfaces;

public record ReviewSubmitResponse(
        ReviewCardResponse card,
        ReviewRecordResponse record
) {
}
