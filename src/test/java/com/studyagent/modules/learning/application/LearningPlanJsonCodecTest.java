package com.studyagent.modules.learning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LearningPlanJsonCodecTest {

    private final LearningPlanJsonCodec codec = new LearningPlanJsonCodec(new ObjectMapper());

    @Test
    void parsePlanShouldReadTodosFromJsonBlock() {
        LearningPlanJsonCodec.TodoPlanResult result = codec.parsePlan("""
                ```json
                {
                  "todos": [
                    {"title": "JVM 内存区域", "description": "理解运行时数据区"},
                    {"title": "GC Roots", "description": "掌握可达性分析"}
                  ]
                }
                ```
                """);

        assertThat(result.todos()).hasSize(2);
        assertThat(result.todos().getFirst().title()).isEqualTo("JVM 内存区域");
        assertThat(result.todos().get(1).description()).isEqualTo("掌握可达性分析");
    }
}
