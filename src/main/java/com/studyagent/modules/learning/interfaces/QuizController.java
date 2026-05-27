package com.studyagent.modules.learning.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.learning.application.QuizService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quizzes")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    @GetMapping("/questions")
    public ApiResponse<List<QuizQuestionResponse>> history(
            @RequestParam(required = false) Long knowledgeBaseId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ApiResponse.ok(quizService.history(knowledgeBaseId, limit));
    }

    @PostMapping("/questions/{questionId}/answers")
    public ApiResponse<QuizAnswerResponse> answer(
            @PathVariable Long questionId,
            @Valid @RequestBody QuizAnswerRequest request
    ) {
        return ApiResponse.ok(quizService.answer(questionId, request.userAnswer()));
    }
}
