package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static com.studyagent.learning.PlanningData.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningEvidenceTest {
    @Test void numberedSpansPreserveFormulaTextWithoutDroppingLongLines() {
        String content = "标题\n" + "x := y × z → tmp; ".repeat(80) + "\n结论：O(n²)\n";
        var spans = PlanningEvidence.excerpts(content);
        assertThat(spans).allSatisfy(s -> {
            assertThat(s.text()).hasSizeLessThanOrEqualTo(400);
            assertThat(content).contains(s.text());
        });
        assertThat(String.join("", spans.stream().map(PlanningEvidence.Excerpt::text).toList()).replaceAll("\\s+", ""))
                .isEqualTo(content.replaceAll("\\s+", ""));
        for (int i = 0; i < spans.size(); i++) { assertThat(spans.get(i).excerptNo()).isEqualTo(i + 1); }
    }

    @Test void fabricatedNumberCannotCreateEvidence() {
        var mapper = new ObjectMapper();
        var source = new Source(1L,"lecture","hash","c1","x := y × z → tmp", "{}");
        assertThatThrownBy(() -> PlanningValidation.extraction(mapper.readTree("""
                {"points":[{"topic":"公式","subtopics":[],"sourceChunkIds":["c1"],
                "evidence":[{"sourceChunkId":"c1","excerptNo":2}]}],"uncovered":[]}
                """), List.of(source))).hasMessageContaining("摘录编号");
    }
}
