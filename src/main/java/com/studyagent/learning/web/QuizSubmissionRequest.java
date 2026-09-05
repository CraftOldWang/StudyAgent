package com.studyagent.learning.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record QuizSubmissionRequest(
        @NotNull @Size(min = 5, max = 5) List<@NotNull String> answers) {
}
