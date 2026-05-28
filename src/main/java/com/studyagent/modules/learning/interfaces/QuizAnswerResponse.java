package com.studyagent.modules.learning.interfaces;

import com.studyagent.modules.learning.domain.QuizAnswer;
import java.time.LocalDateTime;

/**
 * 测验作答响应。
 */
public record QuizAnswerResponse(
        Long id,
        Long questionId,
        Long sessionId,
        String userAnswer,
        String evaluation,
        Boolean correct,
        Integer score,
        LocalDateTime answeredAt
) {

    /**
     * 将作答实体转换为接口响应。
     */
    public static QuizAnswerResponse from(QuizAnswer answer) {
        return new QuizAnswerResponse(
                answer.getId(),
                answer.getQuestionId(),
                answer.getSessionId(),
                answer.getUserAnswer(),
                answer.getEvaluation(),
                answer.getCorrect(),
                answer.getScore(),
                answer.getAnsweredAt()
        );
    }
}
