package com.studyagent.modules.learning.interfaces;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.modules.learning.domain.QuizAnswer;
import com.studyagent.modules.learning.domain.QuizQuestion;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 测验题响应，包含题目、来源 chunk 和历史作答。
 */
public record QuizQuestionResponse(
        Long id,
        Long knowledgeBaseId,
        Long documentId,
        Long sessionId,
        Long agentRunId,
        String questionType,
        String questionText,
        String correctAnswer,
        String explanation,
        List<String> options,
        List<Long> sourceChunkIds,
        String status,
        LocalDateTime createdAt,
        List<QuizAnswerResponse> answers
) {

    /**
     * 将题目实体和作答记录转换为接口响应。
     */
    public static QuizQuestionResponse from(QuizQuestion question, List<QuizAnswer> answers, ObjectMapper objectMapper) {
        return new QuizQuestionResponse(
                question.getId(),
                question.getKnowledgeBaseId(),
                question.getDocumentId(),
                question.getSessionId(),
                question.getAgentRunId(),
                question.getQuestionType(),
                question.getQuestionText(),
                question.getCorrectAnswer(),
                question.getExplanation(),
                readStringList(question.getOptionsJson(), objectMapper),
                readLongList(question.getSourceChunkIdsJson(), objectMapper),
                question.getStatus(),
                question.getCreatedAt(),
                answers.stream().map(QuizAnswerResponse::from).toList()
        );
    }

    /**
     * 读取字符串 JSON 数组，解析失败时返回空列表以保持响应稳定。
     */
    private static List<String> readStringList(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    /**
     * 读取 Long JSON 数组，解析失败时返回空列表以保持响应稳定。
     */
    private static List<Long> readLongList(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }
}
