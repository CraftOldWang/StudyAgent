package com.studyagent.modules.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeepseekGeneratedCasesParserTest {

    private final DeepseekGeneratedCasesParser parser = new DeepseekGeneratedCasesParser(new ObjectMapper());

    @Test
    void parseShouldKeepOnlyExpectedChunkIdsFromProvidedSources() {
        List<GeneratedRagEvalDataset.SourceChunk> sourceChunks = List.of(
                new GeneratedRagEvalDataset.SourceChunk(1L, 10L, 100L, 1000L, "CHILD", 0, "doc", "content"),
                new GeneratedRagEvalDataset.SourceChunk(2L, 10L, 100L, 1000L, "CHILD", 1, "doc", "content")
        );
        String json = """
                {
                  "cases": [
                    {
                      "question": "什么是 RRF？",
                      "expectedAnswer": "RRF 是一种融合排序策略。",
                      "expectedChunkIds": [1, 999],
                      "reason": "chunk 1 提到了 RRF"
                    }
                  ]
                }
                """;

        DeepseekGeneratedCasesParser.ParseResult result = parser.parse(json, sourceChunks);

        assertThat(result.cases()).hasSize(1);
        assertThat(result.cases().getFirst().expectedChunkIds()).containsExactly(1L);
        assertThat(result.warnings()).hasSize(1);
    }
}
