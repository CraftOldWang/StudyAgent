package com.studyagent.learning;

import static com.studyagent.learning.PlanningData.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningValidationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Source source = new Source(1L, "lecture", "hash", "chunk-1", "进程同步与互斥", "{}");
    private final Outline outline = new Outline(List.of(new Chapter(10L, "并发", List.of(
            new Point(20L, "同步", List.of("互斥"), List.of("chunk-1"), List.of(new Evidence("chunk-1", "进程同步与互斥"))),
            new Point(21L, "基础", List.of(), List.of("chunk-1"), List.of(new Evidence("chunk-1", "进程同步与互斥")))))));

    @Test void rejectsOmittedInputAndForeignReferences() throws Exception {
        assertThatThrownBy(() -> PlanningValidation.extraction(mapper.readTree("{\"points\":[],\"uncovered\":[]}"), List.of(source)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("遗漏");
        assertThatThrownBy(() -> PlanningValidation.extraction(mapper.readTree("""
                {"points":[{"topic":"同步","subtopics":[],"sourceChunkIds":["foreign"]}],"uncovered":[]}
                """), List.of(source))).hasMessageContaining("本批次以外");
    }

    @Test void retainsUninformativeSegmentsWithExplicitReason() throws Exception {
        var result = PlanningValidation.extraction(mapper.readTree("""
                {"points":[],"uncovered":[{"sourceChunkId":"chunk-1","reason":"目录"}]}
                """), List.of(source));
        assertThat(result.uncovered()).hasSize(1);
    }

    @Test void mergingUnionsReferencesAndCannotDiscardCandidate() throws Exception {
        var candidates = List.of(new Candidate(1L,"同步",List.of(),List.of("c1"),List.of(new Evidence("c1","同步"))),
                new Candidate(2L,"同步机制",List.of(),List.of("c2"),List.of(new Evidence("c2","同步机制"))));
        var valid = PlanningValidation.outline(mapper.readTree("""
                {"points":[{"chapterTitle":"并发","topic":"同步","subtopics":[],"candidateIds":["1","2"]}]}
                """), candidates, null);
        assertThat(valid.chapters().getFirst().points().getFirst().sourceChunkIds()).containsExactly("c1","c2");
        assertThatThrownBy(() -> PlanningValidation.outline(mapper.readTree("""
                {"points":[{"chapterTitle":"并发","topic":"同步","subtopics":[],"candidateIds":["1"]}]}
                """), candidates, null)).hasMessageContaining("全部候选");
    }

    @Test void requestedPointCountIsCheckedAcrossChapters() throws Exception {
        var candidates = List.of(new Candidate(1L,"同步",List.of(),List.of("c1"),List.of(new Evidence("c1","同步"))));
        assertThatThrownBy(() -> PlanningValidation.outline(mapper.readTree("""
                {"points":[{"chapterTitle":"并发","topic":"同步","subtopics":[],"candidateIds":["1"]}]}
                """), candidates, 5)).hasMessageContaining("数量");
    }

    @Test void extractionKeepsVerifiedQuotesForBoundedDownstreamMatching() throws Exception {
        var result = PlanningValidation.extraction(mapper.readTree("""
                {"points":[{"topic":"同步","subtopics":[],"sourceChunkIds":["chunk-1"],
                "evidence":[{"sourceChunkId":"chunk-1","quote":"进程同步"}]}],"uncovered":[]}
                """), List.of(source));
        assertThat(result.points().getFirst().evidence()).containsExactly(new Evidence("chunk-1","进程同步"));
        assertThatThrownBy(() -> PlanningValidation.emphasis(mapper.readTree("""
                {"matches":[{"knowledgePointId":"20","sourceChunkId":"chunk-1","quote":"同步",
                "lessonSourceChunkId":"foreign","lessonQuote":"同步","reason":"相关","priority":"HIGH"}],"unmatched":[]}
                """), outline, List.of(source), List.of(source))).hasMessageContaining("被映射知识点");
    }

    @Test void parentMayContainTeachingTextAndUnparsedImageNotes() throws Exception {
        var result = PlanningValidation.extraction(mapper.readTree("""
                {"points":[{"topic":"同步","subtopics":[],"sourceChunkIds":["chunk-1"],
                "evidence":[{"sourceChunkId":"chunk-1","quote":"进程同步"}]}],
                "uncovered":[{"sourceChunkId":"chunk-1","reason":"图片占位内容未提取"}]}
                """), List.of(source));
        assertThat(result.points()).hasSize(1);
        assertThat(result.uncovered()).hasSize(1);
    }

    @Test void emphasisRejectsFabricatedQuoteAndUnknownTeachingId() throws Exception {
        String format = """
                {"matches":[{"knowledgePointId":"%s","sourceChunkId":"chunk-1","quote":"%s","reason":"解释","priority":"HIGH"}],"unmatched":[]}
                """;
        assertThatThrownBy(() -> PlanningValidation.emphasis(mapper.readTree(format.formatted("20", "死锁预防")), outline, List.of(source), List.of(source)))
                .hasMessageContaining("原文摘录");
        assertThatThrownBy(() -> PlanningValidation.emphasis(mapper.readTree(format.formatted("99", "进程同步")), outline, List.of(source), List.of(source)))
                .hasMessageContaining("既有知识点");
    }

    @Test void preservesUnmatchedExercisesAndAllocatesExtraTimeWithoutDeletingFoundation() throws Exception {
        var emphasis = PlanningValidation.emphasis(mapper.readTree("""
                {"matches":[{"knowledgePointId":"20","sourceChunkId":"chunk-1","quote":"进程同步","lessonSourceChunkId":"chunk-1","lessonQuote":"进程同步","reason":"同步题","priority":"HIGH"}],
                 "unmatched":[{"sourceChunkId":"chunk-1","quote":"互斥","reason":"后续细分"}]}
                """), outline, List.of(source), List.of(source));
        var tasks = PlanningValidation.tasks(mapper.readTree("""
                {"tasks":[{"knowledgePointId":"21","baseMinutes":15,"reason":"先修"},
                          {"knowledgePointId":"20","baseMinutes":15,"reason":"重点练习"}]}
                """), outline, emphasis);
        assertThat(tasks).extracting(Task::estimatedMinutes).containsExactly(15,25);
        assertThat(tasks).extracting(Task::priority).containsExactly("NORMAL","HIGH");
        assertThat(emphasis.unmatched()).hasSize(1);
        assertThatThrownBy(() -> PlanningValidation.tasks(mapper.readTree("""
                {"tasks":[{"knowledgePointId":"20","baseMinutes":15,"reason":"只学重点"}]}
                """), outline, emphasis)).hasMessageContaining("基础知识点");
    }
}
