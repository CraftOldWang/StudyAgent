package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

class LearningPlanServiceTest {

    private final Model model = mock(Model.class);
    private final LearningPlanService service = new LearningPlanService(model, new ObjectMapper());

    @Test
    void generatesPlanThroughOneAgentScopeModelCallAndMapsOnlyPlanFields() {
        when(model.stream(any(), eq(List.of()), any(GenerateOptions.class)))
                .thenReturn(Flux.just(response("""
                        [
                          {
                            "topic": "Java 面向对象",
                            "subtopics": ["封装", "继承", "多态"],
                            "estimatedMinutes": 45
                          },
                          {
                            "topic": "接口设计",
                            "subtopics": ["接口", "组合"],
                            "estimatedMinutes": 30
                          }
                        ]
                        """)));

        List<LearningPlanItem> plan = service.generatePlan("学习 Java 面向对象");

        assertThat(plan).containsExactly(
                new LearningPlanItem("Java 面向对象", List.of("封装", "继承", "多态"), 45),
                new LearningPlanItem("接口设计", List.of("接口", "组合"), 30));
        ArgumentCaptor<List<Msg>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<GenerateOptions> options = ArgumentCaptor.forClass(GenerateOptions.class);
        verify(model, times(1)).stream(messages.capture(), eq(List.of()), options.capture());
        assertThat(messages.getValue()).extracting(Msg::getTextContent)
                .anySatisfy(content -> assertThat(content).contains("学习 Java 面向对象"));
        assertThat(options.getValue().getStream()).isFalse();
    }

    @Test
    void rejectsMissingGoalBeforeCallingModel() {
        assertThatThrownBy(() -> service.generatePlan("  "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("学习目标不能为空");

        verifyNoInteractions(model);
    }

    @Test
    void exposesModelFailureWithoutFallbackOrRetry() {
        when(model.stream(any(), eq(List.of()), any(GenerateOptions.class)))
                .thenReturn(Flux.error(new IllegalStateException("provider unavailable")));

        assertThatThrownBy(() -> service.generatePlan("学习 Java"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("调用 DeepSeek 生成学习计划失败")
                .hasMessageContaining("provider unavailable");

        verify(model, times(1)).stream(any(), eq(List.of()), any(GenerateOptions.class));
    }

    @Test
    void rejectsNonArrayAndMalformedPlanFields() {
        when(model.stream(any(), eq(List.of()), any(GenerateOptions.class)))
                .thenReturn(Flux.just(response("{\"topic\":\"Java\"}")))
                .thenReturn(Flux.just(response("[{\"topic\":\"Java\",\"subtopics\":[],\"estimatedMinutes\":0}]")));

        assertThatThrownBy(() -> service.generatePlan("学习 Java"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须是 JSON 数组");
        assertThatThrownBy(() -> service.generatePlan("学习 Java"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("estimatedMinutes 必须是正整数");
    }

    @Test
    void rejectsMarkdownAndUnknownFieldsInsteadOfSilentlyRepairingJson() {
        when(model.stream(any(), eq(List.of()), any(GenerateOptions.class)))
                .thenReturn(Flux.just(response("```json\n[]\n```")))
                .thenReturn(Flux.just(response("[{\"topic\":\"Java\",\"subtopics\":[],\"estimatedMinutes\":30,\"extra\":true}]")));

        assertThatThrownBy(() -> service.generatePlan("学习 Java"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解析 DeepSeek 学习计划 JSON 失败");
        assertThatThrownBy(() -> service.generatePlan("学习 Java"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须恰好包含");
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
