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
                new GeneratedRagEvalDataset.SourceChunk("chunk-1", 10L, 100L, "parent-1", "CHILD", 0, "doc", "content"),
                new GeneratedRagEvalDataset.SourceChunk("chunk-2", 10L, 100L, "parent-1", "CHILD", 1, "doc", "content")
        );
        String json = """
                {
                  "cases": [
                    {
                      "question": "什么是 RRF？",
                      "expectedAnswer": "RRF 是一种融合排序策略。",
                      "expectedChunkIds": ["chunk-1", "missing"],
                      "reason": "chunk 1 提到了 RRF"
                    }
                  ]
                }
                """;

        DeepseekGeneratedCasesParser.ParseResult result = parser.parse(json, sourceChunks);

        assertThat(result.cases()).hasSize(1);
        assertThat(result.cases().getFirst().expectedChunkIds()).containsExactly("chunk-1");
        assertThat(result.warnings()).hasSize(1);
    }

    @Test
    void parseShouldIgnoreIntegerChunkIds() {
        List<GeneratedRagEvalDataset.SourceChunk> sourceChunks = List.of(
                new GeneratedRagEvalDataset.SourceChunk("chunk-1", 10L, 100L, "parent-1", "CHILD", 0, "doc", "content")
        );
        String json = """
                {
                  "cases": [
                    {
                      "question": "什么是 RRF？",
                      "expectedAnswer": "RRF 是一种融合排序策略。",
                      "expectedChunkIds": [1]
                    }
                  ]
                }
                """;

        DeepseekGeneratedCasesParser.ParseResult result = parser.parse(json, sourceChunks);

        assertThat(result.cases()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
    }
}
