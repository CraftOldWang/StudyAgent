package com.studyagent.learning;

import com.studyagent.agent.integration.KnowledgeSearchExecution;
import com.studyagent.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

/** Model tools stage one validated business effect; the application commits it with the completed turn. */
public final class LearningTurnIntent {
    public enum Action { EXPLANATION, QUIZ, GRADE, PREPARE_CARDS, CARDS }
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
    private Set<String> retainedSources = Set.of();
    private Runnable onBeginCards = () -> { };

    public LearningTurnIntent(KnowledgePointStatus current, KnowledgeSearchExecution search,
                              String userMessage, List<QuizQuestionDraft> currentQuiz) {
        this.current = current;
        this.search = search;
        this.currentQuiz = currentQuiz == null ? List.of() : List.copyOf(currentQuiz);
        this.submittedChoices = QuizAnswerParser.parse(userMessage, this.currentQuiz.size());
    }

    public synchronized void explanationDone() {
        requireSearch();
        reserve(Action.EXPLANATION, KnowledgePointStatus.EXPLAINING);
    }

    public synchronized void publishQuiz(List<QuizQuestionDraft> drafts) {
        requireSearch();
        List<QuizQuestionDraft> validated = LearningArtifactValidator.quiz(drafts, availableSources());
        reserve(Action.QUIZ, KnowledgePointStatus.QUIZZING);
        questions = validated;
    }

    public synchronized void submitQuiz() {
        if (submittedChoices.isEmpty()) {
            throw new BusinessException("请用户按1.A、2.B这样的编号格式，完整提交当前测验的全部答案");
        }
        reserve(Action.GRADE, KnowledgePointStatus.FEEDBACK);
        List<String> received = new ArrayList<>();
        List<QuizFeedback> evaluated = new ArrayList<>();
        int correct = 0;
        for (int i = 0; i < currentQuiz.size(); i++) {
            QuizQuestionDraft question = currentQuiz.get(i);
            String answer = question.options().get(submittedChoices.get().get(i));
            boolean accepted = answer.equals(question.correctAnswer());
            received.add(answer);
            evaluated.add(new QuizFeedback(i, accepted, question.correctAnswer(), question.explanation()));
            if (accepted) { correct++; }
        }
        answers = List.copyOf(received);
        feedback = List.copyOf(evaluated);
        score = (int) Math.round(correct * 100.0 / currentQuiz.size());
    }

    public void onBeginCards(Runnable callback) { this.onBeginCards = callback; }

    public synchronized void beginCards() {
        if (current != KnowledgePointStatus.FEEDBACK || action != null) {
            throw new BusinessException("请在练习后答疑完成、用户要求继续时进入卡片阶段");
        }
        onBeginCards.run();
        action = Action.PREPARE_CARDS;
    }

    public synchronized void publishCards(List<GeneratedCard> drafts) {
        requireSearch();
        List<GeneratedCard> validated = LearningArtifactValidator.cards(drafts, availableSources());
        if (current != KnowledgePointStatus.CARD_GENERATING && action != Action.PREPARE_CARDS) {
            throw new BusinessException("请先调用learning_cards_begin进入卡片阶段");
        }
        if (action != null && action != Action.PREPARE_CARDS) { throw new BusinessException("本轮已写入学习产物"); }
        action = Action.CARDS;
        cards = validated;
    }

    public void retainSources(Set<String> sources) { retainedSources = Set.copyOf(sources); }

    private Set<String> availableSources() {
        Set<String> sources = new HashSet<>(retainedSources);
        sources.addAll(search.retrievedChunkIds());
        return sources;
    }

    private void requireSearch() {
        if (availableSources().isEmpty()) {
            throw new BusinessException("请先通过knowledge_read或knowledge_search取得真实资料依据；当前知识点历史中已读取的资料也可以使用");
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
