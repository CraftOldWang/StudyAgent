package com.studyagent.modules.learning.interfaces;

import com.studyagent.modules.learning.domain.QuizAnswer;
import java.time.LocalDateTime;

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
