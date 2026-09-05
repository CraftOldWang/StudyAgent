package com.studyagent.learning.web;

import jakarta.validation.constraints.NotBlank;

public record LearningMessageRequest(@NotBlank String message) {
}
