package com.studyagent.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

public final class LearningActionTool implements AgentTool {
    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final LearningTurnIntent.Action action;
    private final ObjectMapper mapper;

    public LearningActionTool(String name, String description, Map<String, Object> parameters,
                              LearningTurnIntent.Action action, ObjectMapper mapper) {
        this.name = name; this.description = description; this.parameters = parameters; this.action = action; this.mapper = mapper;
    }
    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public Map<String, Object> getParameters() { return parameters; }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            if (param == null || param.getRuntimeContext() == null) { throw new BusinessException("学习工具缺少服务端作用域"); }
            LearningTurnIntent intent = param.getRuntimeContext().get(LearningTurnIntent.class);
            if (intent == null) { throw new BusinessException("学习工具不在当前学习回合中"); }
            Map<String, Object> input = param.getInput() == null ? Map.of() : param.getInput();
            Set<String> required = switch (action) {
                case QUIZ -> Set.of("questions");
                case CARDS -> Set.of("cards");
                default -> Set.of();
            };
            if (!input.keySet().equals(required)) { throw new BusinessException("学习工具字段与约定不符"); }
            switch (action) {
                case EXPLANATION -> intent.explanationDone();
                case QUIZ -> intent.publishQuiz(mapper.convertValue(input.get("questions"), new TypeReference<List<QuizQuestionDraft>>() { }));
                case GRADE -> intent.submitQuiz();
                case CARDS -> intent.publishCards(mapper.convertValue(input.get("cards"), new TypeReference<List<GeneratedCard>>() { }));
            }
            return ToolResultBlock.of(param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId(), name,
                    TextBlock.builder().text("{\"accepted\":true,\"pendingCommit\":true,\"action\":\"" + action + "\"}").build());
        });
    }
}
