package com.studyagent.modules.knowledge.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.knowledge.domain.KnowledgeBase;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping
    public ApiResponse<List<KnowledgeBase>> list() {
        return ApiResponse.ok(knowledgeBaseService.list(KnowledgeBaseService.DEFAULT_USER_ID));
    }

    @PostMapping
    public ApiResponse<KnowledgeBase> create(@Valid @RequestBody KnowledgeBaseCreateRequest request) {
        return ApiResponse.ok(knowledgeBaseService.create(
                KnowledgeBaseService.DEFAULT_USER_ID,
                request.name(),
                request.description()
        ));
    }

    @GetMapping("/default")
    public ApiResponse<KnowledgeBase> defaultKnowledgeBase() {
        return ApiResponse.ok(knowledgeBaseService.getOrCreateDefault(KnowledgeBaseService.DEFAULT_USER_ID));
    }

    @GetMapping("/{knowledgeBaseId}")
    public ApiResponse<KnowledgeBase> get(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.ok(knowledgeBaseService.get(KnowledgeBaseService.DEFAULT_USER_ID, knowledgeBaseId));
    }

    @PatchMapping("/{knowledgeBaseId}")
    public ApiResponse<KnowledgeBase> update(
            @PathVariable Long knowledgeBaseId,
            @RequestBody KnowledgeBaseUpdateRequest request
    ) {
        return ApiResponse.ok(knowledgeBaseService.update(
                KnowledgeBaseService.DEFAULT_USER_ID,
                knowledgeBaseId,
                request.name(),
                request.description(),
                request.status()
        ));
    }

    @DeleteMapping("/{knowledgeBaseId}")
    public ApiResponse<Void> delete(@PathVariable Long knowledgeBaseId) {
        knowledgeBaseService.delete(KnowledgeBaseService.DEFAULT_USER_ID, knowledgeBaseId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public ApiResponse<List<DocumentResponse>> listDocuments(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.ok(knowledgeBaseService.listDocuments(KnowledgeBaseService.DEFAULT_USER_ID, knowledgeBaseId)
                .stream()
                .map(DocumentResponse::from)
                .toList());
    }
}
