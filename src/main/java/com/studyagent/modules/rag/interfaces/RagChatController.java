package com.studyagent.modules.rag.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.rag.application.RagService;
import com.studyagent.modules.rag.domain.RagAnswer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class RagChatController {

    private final RagService ragService;

    @PostMapping("/rag")
    public ApiResponse<RagAnswer> rag(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(ragService.answer(request.knowledgeBaseId(), request.question()));
    }
}
