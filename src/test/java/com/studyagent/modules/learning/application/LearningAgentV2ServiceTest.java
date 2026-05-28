package com.studyagent.modules.learning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.modules.learning.domain.AgentRun;
import com.studyagent.modules.learning.domain.AgentStepRecord;
import com.studyagent.modules.learning.domain.ChatMessage;
import com.studyagent.modules.learning.domain.ChatSession;
import com.studyagent.modules.learning.domain.LearningTodo;
import com.studyagent.modules.learning.infrastructure.AgentRunMapper;
import com.studyagent.modules.learning.infrastructure.AgentStepRecordMapper;
import com.studyagent.modules.learning.infrastructure.ChatMessageMapper;
import com.studyagent.modules.learning.infrastructure.ChatSessionMapper;
import com.studyagent.modules.learning.infrastructure.LearningTodoMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LearningAgentV2ServiceTest {

    @Mock
    private ChatSessionMapper chatSessionMapper;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private AgentRunMapper agentRunMapper;

    @Mock
    private AgentStepRecordMapper agentStepRecordMapper;

    @Mock
    private LearningTodoMapper learningTodoMapper;

    @Mock
    private ContextMemoryService contextMemoryService;

    @Mock
    private AgentPlannerService agentPlannerService;

    @Mock
    private ResponseWriterService responseWriterService;

    @Test
    void runSessionShouldNotPersistAssistantMessageWhenWriterFailsAfterTokens() {
        LearningAgentV2Service service = new LearningAgentV2Service(
                chatSessionMapper,
                chatMessageMapper,
                agentRunMapper,
                agentStepRecordMapper,
                learningTodoMapper,
                contextMemoryService,
                null,
                new LearningAgentV2JsonCodec(new ObjectMapper()),
                agentPlannerService,
                responseWriterService,
                new ToolCallTraceCollector(new ObjectMapper()),
                new ObjectMapper()
        );
        ChatSession session = session();
        AgentRun run = run();
        LearningTodo todo = todo();
        when(chatSessionMapper.selectById(1L)).thenReturn(session);
        when(agentRunMapper.selectRunningBySession(1L, 100L)).thenReturn(run);
        when(chatMessageMapper.selectAfter(1L, 0L)).thenReturn(messages());
        when(contextMemoryService.restore(1L))
                .thenReturn(new ContextMemoryService.RestoredContext(null, List.of()));
        when(learningTodoMapper.countBySession(1L)).thenReturn(1L);
        when(learningTodoMapper.selectCurrent(1L)).thenReturn(todo);
        when(learningTodoMapper.selectBySession(1L)).thenReturn(List.of(todo));
        when(agentPlannerService.decide(any())).thenReturn(new AgentPlannerService.PlannerResult(
                new AgentPlannerDecision(
                        "TEACH",
                        "NEEDS_USER_INPUT",
                        "WAIT_USER",
                        "讲解当前知识点",
                        "",
                        "用户继续学习"
                ),
                """
                        {
                          "phase": "TEACH",
                          "currentTopicStatus": "NEEDS_USER_INPUT",
                          "nextAction": "WAIT_USER",
                          "responsePlan": "讲解当前知识点"
                        }
                        """,
                "",
                false
        ));
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(1);
            consumer.accept("已经输出的 token");
            throw new IllegalStateException("writer boom");
        }).when(responseWriterService).stream(any(), any());
        List<LearningAgentEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> service.runSession(1L, "继续", events::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("writer boom");

        assertThat(events).extracting(LearningAgentEvent::event)
                .contains("token.delta", "error");
        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        org.mockito.Mockito.verify(chatMessageMapper, org.mockito.Mockito.atMostOnce()).insert(messageCaptor.capture());
        assertThat(messageCaptor.getAllValues())
                .allSatisfy(inserted -> {
                    assertThat(inserted.getRole()).isNotEqualTo("ASSISTANT");
                    assertThat(inserted.getMessageType()).isNotEqualTo("TOPIC_REPLY");
                });
        verify(learningTodoMapper, org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.<LearningTodo>any());
        verify(contextMemoryService, org.mockito.Mockito.never()).compressAfterRound(any(), any(), any());
        assertThat(run.getErrorMessage()).isEqualTo("writer boom");
        verify(agentRunMapper, org.mockito.Mockito.atLeastOnce()).updateById(run);
        verify(agentStepRecordMapper, org.mockito.Mockito.atLeastOnce())
                .updateById(org.mockito.ArgumentMatchers.<AgentStepRecord>any());
    }

    private ChatSession session() {
        ChatSession session = new ChatSession();
        session.setId(1L);
        session.setUserId(100L);
        session.setMode("LEARNING_AGENT_V2");
        session.setStatus("ACTIVE");
        session.setKnowledgeBaseScopeJson("[200]");
        return session;
    }

    private AgentRun run() {
        AgentRun run = new AgentRun();
        run.setId(10L);
        run.setSessionId(1L);
        run.setUserId(100L);
        run.setStatus("RUNNING");
        run.setCurrentStage("TEACH");
        return run;
    }

    private LearningTodo todo() {
        LearningTodo todo = new LearningTodo();
        todo.setId(20L);
        todo.setSessionId(1L);
        todo.setUserId(100L);
        todo.setTitle("JVM 内存区域");
        todo.setDescription("理解运行时数据区");
        todo.setStatus("LEARNING");
        todo.setOrderIndex(1);
        return todo;
    }

    private List<ChatMessage> messages() {
        ChatMessage goal = new ChatMessage();
        goal.setId(1L);
        goal.setSessionId(1L);
        goal.setUserId(100L);
        goal.setRole("USER");
        goal.setMessageType("TEXT");
        goal.setContent("学习 JVM");
        ChatMessage latest = new ChatMessage();
        latest.setId(2L);
        latest.setSessionId(1L);
        latest.setUserId(100L);
        latest.setRole("USER");
        latest.setMessageType("TEXT");
        latest.setContent("继续");
        return List.of(goal, latest);
    }
}
