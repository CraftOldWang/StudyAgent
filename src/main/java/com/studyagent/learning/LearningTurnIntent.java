package com.studyagent.learning;

import com.studyagent.agent.integration.KnowledgeSearchExecution;
import com.studyagent.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Model tools stage one validated business effect; the application commits it with the completed turn. */
public final class LearningTurnIntent {
    public enum Action { EXPLANATION, QUIZ, GRADE, CARDS }
    private final KnowledgePointStatus current;
    private final KnowledgeSearchExecution search;
    private final Optional<List<Integer>> submittedChoices;
    private final List<QuizQuestionDraft> currentQuiz;
    private Action action;
    private List<QuizQuestionDraft> questions;
    private List<GeneratedCard> cards;
    private List<String> answers;
    private List<QuizFeedback> feedback;
    private int score;

    public LearningTurnIntent(KnowledgePointStatus current, KnowledgeSearchExecution search,
                              String userMessage, List<QuizQuestionDraft> currentQuiz) {
        this.current = current;
        this.search = search;
        this.submittedChoices = QuizAnswerParser.parse(userMessage);
        this.currentQuiz = currentQuiz == null ? List.of() : List.copyOf(currentQuiz);
    }

    public synchronized void explanationDone() {
        requireSearch();
        reserve(Action.EXPLANATION, KnowledgePointStatus.EXPLAINING);
    }

    public synchronized void publishQuiz(List<QuizQuestionDraft> drafts) {
        requireSearch();
        List<QuizQuestionDraft> validated = LearningArtifactValidator.quiz(drafts, search.retrievedChunkIds());
        reserve(Action.QUIZ, KnowledgePointStatus.QUIZZING);
        questions = validated;
    }

    public synchronized void submitQuiz() {
        if (submittedChoices.isEmpty() || currentQuiz.size() != 5) {
            throw new BusinessException("答案不完整或有歧义，请用户按 1.A 2.B 3.C 4.D 5.A 一次明确提交五题答案");
        }
        reserve(Action.GRADE, KnowledgePointStatus.CARD_GENERATING);
        List<String> received = new ArrayList<>();
        List<QuizFeedback> evaluated = new ArrayList<>();
        int correct = 0;
        for (int i = 0; i < 5; i++) {
            QuizQuestionDraft question = currentQuiz.get(i);
            String answer = question.options().get(submittedChoices.get().get(i));
            boolean accepted = answer.equals(question.correctAnswer());
            received.add(answer);
            evaluated.add(new QuizFeedback(i, accepted, question.correctAnswer(), question.explanation()));
            if (accepted) { correct++; }
        }
        answers = List.copyOf(received);
        feedback = List.copyOf(evaluated);
        score = correct * 20;
    }

    public synchronized void publishCards(List<GeneratedCard> drafts) {
        requireSearch();
        List<GeneratedCard> validated = LearningArtifactValidator.cards(drafts, search.retrievedChunkIds());
        reserve(Action.CARDS, KnowledgePointStatus.COMPLETED);
        cards = validated;
    }

    private void requireSearch() {
        if (!search.invoked() || search.retrievedChunkIds().isEmpty()) {
            throw new BusinessException("请先检索当前知识库并取得真实资料依据，未找到资料时说明不足，不提交学习产物");
        }
    }
    private void reserve(Action proposed, KnowledgePointStatus target) {
        if (action != null) { throw new BusinessException("同一轮最多提交一次学习状态转换"); }
        try { new KnowledgePointLifecycle().advance(current, target); }
        catch (IllegalStateException error) { throw new BusinessException("当前知识点状态不允许此操作：" + current + " → " + target); }
        action = proposed;
    }
    public synchronized Action action() { return action; }
    public synchronized List<QuizQuestionDraft> questions() { return questions; }
    public synchronized List<GeneratedCard> cards() { return cards; }
    public synchronized List<String> answers() { return answers; }
    public synchronized List<QuizFeedback> feedback() { return feedback; }
    public synchronized int score() { return score; }
}
