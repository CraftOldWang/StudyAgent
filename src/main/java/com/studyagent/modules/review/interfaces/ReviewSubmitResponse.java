package com.studyagent.modules.review.interfaces;

/**
 * 提交复习后的响应，包含更新后的卡片和本次复习记录。
 */
public record ReviewSubmitResponse(
        ReviewCardResponse card,
        ReviewRecordResponse record
) {
}
