package com.studyagent.modules.learning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.ai.ChatGenerationService;
import com.studyagent.modules.learning.domain.ChatSession;
import com.studyagent.modules.learning.domain.LearningTodo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentPlannerServiceTest {

    @Mock
    private ChatGenerationService chatGenerationService;

    @Test
    void decideShouldRepairInvalidPlannerJsonOnce() {
        AgentPlannerService service = new AgentPlannerService(
                chatGenerationService,
                new AgentPlannerJsonCodec(new ObjectMapper())
        );
        when(chatGenerationService.plannerWithTools(any(), any(), any(), any()))
                .thenReturn("phase=TEACH");
        when(chatGenerationService.generate(any(), any()))
                .thenReturn("""
                        {
                          "phase": "TEACH",
                          "currentTopicStatus": "NEEDS_USER_INPUT",
                          "nextAction": "WAIT_USER",
                          "responsePlan": "讲解 JVM 内存区域",
                          "reason": "修复为合法结构"
                        }
                        """);

        AgentPlannerService.PlannerResult result = service.decide(request());

        assertThat(result.repairAttempted()).isTrue();
        assertThat(result.rawOutput()).isEqualTo("phase=TEACH");
        assertThat(result.decision().phase()).isEqualTo("TEACH");
        verify(chatGenerationService).generate(any(), any());
    }

    @Test
    void decideShouldFailWhenRepairStillInvalid() {
        AgentPlannerService service = new AgentPlannerService(
                chatGenerationService,
                new AgentPlannerJsonCodec(new ObjectMapper())
        );
        when(chatGenerationService.plannerWithTools(any(), any(), any(), any()))
                .thenReturn("phase=TEACH");
        when(chatGenerationService.generate(any(), any()))
                .thenReturn("""
                        {
                          "phase": "CARD",
                          "currentTopicStatus": "IN_PROGRESS",
                          "nextAction": "WAIT_USER",
                          "responsePlan": "写卡"
                        }
                        """);

        assertThatThrownBy(() -> service.decide(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法 Agent phase");
    }

    @Test
    void decideShouldNotRepairLegalPlannerJson() {
        AgentPlannerService service = new AgentPlannerService(
                chatGenerationService,
                new AgentPlannerJsonCodec(new ObjectMapper())
        );
        when(chatGenerationService.plannerWithTools(any(), any(), any(), any()))
                .thenReturn("""
                        {
                          "phase": "QA",
                          "currentTopicStatus": "IN_PROGRESS",
                          "nextAction": "CONTINUE_TOPIC",
                          "responsePlan": "回答用户追问，并提醒可以继续练习",
                          "reason": "用户在追问"
                        }
                        """);

        AgentPlannerService.PlannerResult result = service.decide(request());

        assertThat(result.repairAttempted()).isFalse();
        assertThat(result.decision().phase()).isEqualTo("QA");
        verify(chatGenerationService, never()).generate(any(), any());
    }

    private AgentPlannerService.PlannerRequest request() {
        ChatSession session = new ChatSession();
        session.setId(1L);
        session.setUserId(2L);
        LearningTodo todo = new LearningTodo();
        todo.setId(10L);
        todo.setTitle("JVM 内存区域");
        todo.setDescription("理解运行时数据区");
        todo.setStatus("LEARNING");
        ToolCallTraceCollector.Trace trace = new ToolCallTraceCollector(new ObjectMapper()).create(event -> {
        });
        return new AgentPlannerService.PlannerRequest(
                session,
                todo,
                "学习 JVM",
                "继续",
                "无",
                "1. [LEARNING] JVM 内存区域",
                List.of(100L),
                Map.of(),
                trace
        );
    }
}
