package com.studyagent.modules.learning.interfaces;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 创建学习会话请求，必须声明初始消息和知识库范围。
 */
public record LearningSessionRequest(
        @NotBlank String message,
        @NotEmpty List<Long> knowledgeBaseIds
) {
}
