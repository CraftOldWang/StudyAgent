package com.studyagent.modules.learning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallTraceCollectorTest {

    @Test
    void traceShouldCollectToolEventsAndConvertToSseData() {
        List<LearningAgentEvent> events = new ArrayList<>();
        ToolCallTraceCollector.Trace trace = new ToolCallTraceCollector(new ObjectMapper()).create(events::add);

        trace.onToolStarted("knowledge_search", "{\"question\":\"RRF\"}");
        trace.onToolCompleted("knowledge_search", """
                {
                  "question": "RRF",
                  "hitCount": 1,
                  "references": [
                    {
                      "chunkId": 10,
                      "documentTitle": "RAG 笔记",
                      "content": "RRF 会融合多路召回排名。"
                    }
                  ]
                }
                """);
        trace.onToolFailed("review_card_write", "{\"drafts\":[]}", "复习卡草稿不能为空");

        assertThat(events).extracting(LearningAgentEvent::event)
                .containsExactly("tool.started", "tool.completed", "tool.failed");
        assertThat(trace.traces()).hasSize(3);
        assertThat(trace.summaryText()).contains("knowledge_search COMPLETED");
        assertThat(trace.referenceSummaryText()).contains("RAG 笔记", "RRF 会融合多路召回排名");

        Object completedData = events.get(1).data();
        assertThat(completedData).isInstanceOf(Map.class);
        Map<?, ?> completedMap = (Map<?, ?>) completedData;
        assertThat(completedMap.containsKey("summary")).isTrue();
    }
}
