package com.studyagent.modules.knowledge.interfaces;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.knowledge.domain.KnowledgeBase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping("/default")
    public ApiResponse<KnowledgeBase> defaultKnowledgeBase() {
        return ApiResponse.ok(knowledgeBaseService.getOrCreateDefault(KnowledgeBaseService.DEFAULT_USER_ID));
    }
}
