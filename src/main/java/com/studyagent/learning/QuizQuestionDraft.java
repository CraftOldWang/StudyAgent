package com.studyagent.learning;

import java.util.List;

public record QuizQuestionDraft(
        String question,
        List<String> options,
        String correctAnswer,
        String explanation,
        String sourceChunkId) {
}
