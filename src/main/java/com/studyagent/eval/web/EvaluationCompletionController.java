package com.studyagent.eval.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.eval.EvaluationCompletionService;
import com.studyagent.identity.CurrentUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("eval")
@RequestMapping("/api/eval/completions")
@RequiredArgsConstructor
public class EvaluationCompletionController {
    private final EvaluationCompletionService service;
    private final CurrentUserContext user;

    @PostMapping
    public ApiResponse<EvaluationCompletionService.Result> complete(@Valid @RequestBody Request request) {
        return ApiResponse.ok(service.complete(user.userId(), request.purpose(), request.promptVersion(),
                request.systemPrompt(), request.prompt(), request.maxTokens() == null ? 3000 : request.maxTokens()));
    }

    public record Request(
            @NotBlank @Pattern(regexp = "[a-z0-9-]{1,32}") String purpose,
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,64}") String promptVersion,
            @NotBlank @Size(max = 8000) String systemPrompt,
            @NotBlank @Size(max = 200000) String prompt,
            @Min(128) @Max(4096) Integer maxTokens) { }
}
