package com.studyagent.rag.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.rag.retrieval.SourceReader;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}/source")
@RequiredArgsConstructor
public class SourceController {
    private final CurrentUserContext user;
    private final SourceReader sources;

    @GetMapping
    public ApiResponse<SourceReader.Source> read(@PathVariable Long knowledgeBaseId, @RequestParam String chunkId) {
        return ApiResponse.ok(sources.read(user.userId(), knowledgeBaseId, chunkId));
    }
}
