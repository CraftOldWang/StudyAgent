package com.studyagent.modules.learning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.modules.knowledge.application.KnowledgeBaseService;
import com.studyagent.modules.learning.domain.QuizAnswer;
import com.studyagent.modules.learning.domain.QuizQuestion;
import com.studyagent.modules.learning.infrastructure.QuizAnswerMapper;
import com.studyagent.modules.learning.infrastructure.QuizQuestionMapper;
import com.studyagent.modules.learning.interfaces.QuizAnswerResponse;
import com.studyagent.modules.learning.interfaces.QuizQuestionResponse;
import com.studyagent.modules.rag.domain.RagReference;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 即时测验应用服务，负责从 RAG 引用生成题目、查询历史和记录作答。
 */
@Service
@RequiredArgsConstructor
public class QuizService {

    private static final Long DEFAULT_USER_ID = KnowledgeBaseService.DEFAULT_USER_ID;

    private final QuizQuestionMapper quizQuestionMapper;
    private final QuizAnswerMapper quizAnswerMapper;
    private final ObjectMapper objectMapper;

    /**
     * 基于本轮检索引用生成最多三道简答题。
     */
    @Transactional
    public List<QuizQuestionResponse> createFromReferences(
            Long userId,
            Long sessionId,
            Long agentRunId,
            List<RagReference> references
    ) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<QuizQuestion> questions = new ArrayList<>();
        int limit = Math.min(3, references.size());
        for (int i = 0; i < limit; i++) {
            RagReference reference = references.get(i);
            // 初版题目直接基于引用片段生成，后续可替换为模型生成题干和解析。
            QuizQuestion question = new QuizQuestion();
            question.setUserId(userId);
            question.setKnowledgeBaseId(reference.knowledgeBaseId());
            question.setDocumentId(reference.documentId());
            question.setSessionId(sessionId);
            question.setAgentRunId(agentRunId);
            question.setQuestionType("SHORT_ANSWER");
            question.setQuestionText("请解释：" + compact(reference.content(), 120));
            question.setCorrectAnswer(compact(reference.content(), 500));
            question.setExplanation("参考资料：" + reference.documentTitle() + " / chunk " + reference.chunkId());
            question.setOptionsJson(toJson(List.of()));
            question.setSourceChunkIdsJson(toJson(List.of(reference.chunkId())));
            question.setStatus("ACTIVE");
            question.setCreatedAt(LocalDateTime.now());
            question.setUpdatedAt(LocalDateTime.now());
            quizQuestionMapper.insert(question);
            questions.add(question);
        }
        return questions.stream()
                .map(question -> QuizQuestionResponse.from(question, List.of(), objectMapper))
                .toList();
    }

    /**
     * 查询当前用户的测验题历史。
     */
    public List<QuizQuestionResponse> history(Long knowledgeBaseId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return quizQuestionMapper.selectHistory(DEFAULT_USER_ID, knowledgeBaseId, safeLimit).stream()
                .map(question -> QuizQuestionResponse.from(
                        question,
                        quizAnswerMapper.selectByQuestion(question.getId(), DEFAULT_USER_ID),
                        objectMapper
                ))
                .toList();
    }

    /**
     * 提交作答并写入评分结果。
     */
    @Transactional
    public QuizAnswerResponse answer(Long questionId, String userAnswer) {
        if (userAnswer == null || userAnswer.isBlank()) {
            throw new BusinessException("作答内容不能为空");
        }
        QuizQuestion question = quizQuestionMapper.selectById(questionId);
        if (question == null || !DEFAULT_USER_ID.equals(question.getUserId())) {
            throw new BusinessException("题目不存在或无权访问");
        }
        int score = score(userAnswer, question.getCorrectAnswer());
        QuizAnswer answer = new QuizAnswer();
        answer.setQuestionId(questionId);
        answer.setUserId(DEFAULT_USER_ID);
        answer.setSessionId(question.getSessionId());
        answer.setUserAnswer(userAnswer.trim());
        answer.setScore(score);
        answer.setCorrect(score >= 70);
        answer.setEvaluation(evaluation(score));
        answer.setAnsweredAt(LocalDateTime.now());
        answer.setCreatedAt(LocalDateTime.now());
        quizAnswerMapper.insert(answer);
        return QuizAnswerResponse.from(answer);
    }

    /**
     * 使用关键词覆盖率做简单评分，后续可替换为更稳健的语义评分。
     */
    private int score(String userAnswer, String correctAnswer) {
        Set<String> userTerms = terms(userAnswer);
        Set<String> correctTerms = terms(correctAnswer);
        if (correctTerms.isEmpty()) {
            return userAnswer.isBlank() ? 0 : 60;
        }
        long hitCount = correctTerms.stream().filter(userTerms::contains).count();
        return (int) Math.round(hitCount * 100.0d / correctTerms.size());
    }

    /**
     * 从中英文混合文本中抽取可比较的关键词。
     */
    private Set<String> terms(String value) {
        Set<String> terms = new LinkedHashSet<>();
        if (value == null) {
            return terms;
        }
        for (String term : value.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (term.length() >= 2) {
                terms.add(term);
            }
        }
        return terms;
    }

    /**
     * 根据分数生成面向学习者的反馈。
     */
    private String evaluation(int score) {
        if (score >= 85) {
            return "回答覆盖了大部分关键点。";
        }
        if (score >= 70) {
            return "回答基本正确，但还可以补充更多细节。";
        }
        if (score >= 40) {
            return "回答命中了部分内容，建议回看标准答案和来源片段。";
        }
        return "回答与标准答案差距较大，建议重新学习相关资料。";
    }

    /**
     * 压缩引用内容，避免题干和答案过长。
     */
    private String compact(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 序列化 JSON 字段，失败时返回明确业务异常。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("JSON 序列化失败: " + ex.getMessage());
        }
    }
}
