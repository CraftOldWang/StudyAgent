package com.studyagent.learning;

import com.studyagent.common.exception.BusinessException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LearningArtifactValidator {
    private LearningArtifactValidator() { }

    public static List<QuizQuestionDraft> quiz(List<QuizQuestionDraft> drafts, Set<String> retrievedSources) {
        require(drafts != null && drafts.size() == 5, "必须一次提交恰好五道测验题");
        Set<String> questions = new HashSet<>();
        for (QuizQuestionDraft q : drafts) {
            require(q != null && text(q.question()) && questions.add(q.question().trim()), "测验题目不可为空或重复");
            require(q.options() != null && q.options().size() == 4 && q.options().stream().allMatch(LearningArtifactValidator::text)
                    && q.options().stream().map(String::trim).distinct().count() == 4, "每题需要四个不同的非空选项");
            require(q.options().contains(q.correctAnswer()), "correctAnswer必须原样复制options中的完整选项文本，不能填A/B/C/D位置编号");
            require(text(q.explanation()), "测验题需有解析");
            require(text(q.sourceChunkId()) && retrievedSources.contains(q.sourceChunkId()), "测验来源必须来自当前实际检索结果");
        }
        return List.copyOf(drafts);
    }

    public static List<GeneratedCard> cards(List<GeneratedCard> drafts, Set<String> retrievedSources) {
        require(drafts != null && drafts.size() == 3, "必须一次提交恰好三张复习卡");
        Set<String> fronts = new HashSet<>();
        for (GeneratedCard c : drafts) {
            require(c != null && text(c.front()) && text(c.back()) && fronts.add(c.front().trim()), "卡片正反面不可为空，正面不可重复");
            require(text(c.sourceChunkId()) && retrievedSources.contains(c.sourceChunkId()), "卡片来源必须来自当前实际检索结果");
        }
        return List.copyOf(drafts);
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean accepted, String message) { if (!accepted) { throw new BusinessException(message); } }
}
