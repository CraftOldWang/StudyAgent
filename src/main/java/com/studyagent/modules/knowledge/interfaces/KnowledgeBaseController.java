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

/**
 * 知识库接口层，暴露知识库管理和文档列表查询能力。
 */
@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 查询当前用户可用知识库列表。
     */
    @GetMapping
    public ApiResponse<List<KnowledgeBase>> list() {
        return ApiResponse.ok(knowledgeBaseService.list(KnowledgeBaseService.DEFAULT_USER_ID));
    }

    /**
     * 创建知识库。
     */
    @PostMapping
    public ApiResponse<KnowledgeBase> create(@Valid @RequestBody KnowledgeBaseCreateRequest request) {
        return ApiResponse.ok(knowledgeBaseService.create(
                KnowledgeBaseService.DEFAULT_USER_ID,
                request.name(),
                request.description()
        ));
    }

    /**
     * 获取或创建默认知识库，便于首次使用时直接上传资料。
     */
    @GetMapping("/default")
    public ApiResponse<KnowledgeBase> defaultKnowledgeBase() {
        return ApiResponse.ok(knowledgeBaseService.getOrCreateDefault(KnowledgeBaseService.DEFAULT_USER_ID));
    }

    /**
     * 查询单个知识库详情。
     */
    @GetMapping("/{knowledgeBaseId}")
    public ApiResponse<KnowledgeBase> get(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.ok(knowledgeBaseService.get(KnowledgeBaseService.DEFAULT_USER_ID, knowledgeBaseId));
    }

    /**
     * 局部更新知识库基础信息或状态。
     */
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

    /**
     * 软删除知识库。
     */
    @DeleteMapping("/{knowledgeBaseId}")
    public ApiResponse<Void> delete(@PathVariable Long knowledgeBaseId) {
        knowledgeBaseService.delete(KnowledgeBaseService.DEFAULT_USER_ID, knowledgeBaseId);
        return ApiResponse.ok(null);
    }

    /**
     * 查询知识库下的文档及其处理状态。
     */
    @GetMapping("/{knowledgeBaseId}/documents")
    public ApiResponse<List<DocumentResponse>> listDocuments(@PathVariable Long knowledgeBaseId) {
        return ApiResponse.ok(knowledgeBaseService.listDocuments(KnowledgeBaseService.DEFAULT_USER_ID, knowledgeBaseId)
                .stream()
                .map(DocumentResponse::from)
                .toList());
    }
}
