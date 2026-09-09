package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.studyagent.config.LearningPlanningProperties;
import com.studyagent.config.LearningPlanningReasoningProperties;
import io.agentscope.core.model.Model;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanningReasoningTest {
    @Test void reasoningChangesOnlyMergeAndReviewFingerprintsAndBudgets() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("deepseek-v4-flash");
        var budgets = new LearningPlanningProperties(4800,28000,6000,600);
        var enabled = new PlanningModel(model, budgets, new LearningPlanningReasoningProperties(true,16000,"high"));
        var disabled = new PlanningModel(model, budgets, new LearningPlanningReasoningProperties(false,16000,"high"));
        for (String stage : new String[]{"EXTRACT/0", "TASKS"}) {
            assertThat(enabled.fingerprintConfiguration(stage)).isEqualTo(disabled.fingerprintConfiguration(stage));
            assertThat(enabled.options(stage).getMaxTokens()).isEqualTo(6000);
        }
        for (String stage : new String[]{"OUTLINE", "EMPHASIS/0", "EMPHASIS_REVIEW/0"}) {
            assertThat(enabled.fingerprintConfiguration(stage)).isNotEqualTo(disabled.fingerprintConfiguration(stage));
            assertThat(enabled.options(stage).getMaxTokens()).isEqualTo(16000);
            assertThat(enabled.options(stage).getAdditionalBodyParams()).containsEntry("thinking", Map.of("type", "enabled"));
        }
    }
}
