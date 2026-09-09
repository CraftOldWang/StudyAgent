package com.studyagent.learning.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record QuizSubmissionRequest(
        @NotNull @Size(min = 1, max = 10) List<@NotNull String> answers) {
}
