package com.studyagent.learning;

public record QuizFeedback(
        int questionIndex,
        boolean correct,
        String correctAnswer,
        String explanation) {
}
