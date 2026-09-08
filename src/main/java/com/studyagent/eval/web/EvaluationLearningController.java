package com.studyagent.eval.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.learning.LearningTurnPersistence;
import com.studyagent.model.LearningContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("eval")
@RequestMapping("/api/eval/learning/sessions")
@RequiredArgsConstructor
public class EvaluationLearningController {
    private final CurrentUserContext user;
    private final LearningTurnPersistence turns;

    @PostMapping("/{sessionId}/compression")
    public ApiResponse<LearningContext> configure(@PathVariable Long sessionId, @Valid @RequestBody Request request) {
        return ApiResponse.ok(turns.configureStrategy(user.userId(), sessionId, request.strategy()));
    }
    public record Request(@NotBlank String strategy) { }
}
