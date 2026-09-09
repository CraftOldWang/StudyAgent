package com.studyagent.review.web;

import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.review.AnkiExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review/cards/{cardId}/anki")
@RequiredArgsConstructor
public class AnkiExportController {
    private final AnkiExportService service;
    private final CurrentUserContext user;

    @GetMapping
    public ApiResponse<AnkiExportService.ExportStatus> status(@PathVariable Long cardId) {
        return ApiResponse.ok(service.status(user.userId(), cardId));
    }

    @PostMapping
    public ApiResponse<AnkiExportService.ExportStatus> export(@PathVariable Long cardId) {
        return ApiResponse.ok(service.export(user.userId(), cardId));
    }
}
