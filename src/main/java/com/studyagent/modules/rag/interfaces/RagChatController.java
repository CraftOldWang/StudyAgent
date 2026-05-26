package com.studyagent.modules.rag.interfaces;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.rag.application.RagService;
import com.studyagent.modules.rag.domain.RagAnswer;
import com.studyagent.modules.rag.domain.RagSearchResult;
import jakarta.validation.Valid;
import java.util.List;
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
        return ApiResponse.ok(ragService.answer(resolveKnowledgeBaseId(request), request.question()));
    }

    @PostMapping("/rag/search")
    public ApiResponse<RagSearchResult> search(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(ragService.search(
                KnowledgeBaseService.DEFAULT_USER_ID,
                resolveKnowledgeBaseIds(request),
                request.question()
        ));
    }

    private Long resolveKnowledgeBaseId(ChatRequest request) {
        if (request.knowledgeBaseId() != null) {
            return request.knowledgeBaseId();
        }
        List<Long> ids = resolveKnowledgeBaseIds(request);
        return ids.getFirst();
    }

    private List<Long> resolveKnowledgeBaseIds(ChatRequest request) {
        if (request.knowledgeBaseIds() != null && !request.knowledgeBaseIds().isEmpty()) {
            return request.knowledgeBaseIds();
        }
        if (request.knowledgeBaseId() == null) {
            throw new BusinessException("知识库 ID 不能为空");
        }
        return List.of(request.knowledgeBaseId());
    }
}
