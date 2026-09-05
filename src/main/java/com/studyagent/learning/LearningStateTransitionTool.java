package com.studyagent.learning;

import com.studyagent.common.exception.BusinessException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public final class LearningStateTransitionTool implements AgentTool {
    public static final String TOOL_NAME = "learning_state_transition";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "请求服务端在本次 Agent turn 成功后推进当前知识点状态；模型只能提交目标状态。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("target", Map.of(
                        "type", "string",
                        "enum", List.of("EXPLAINING", "QUIZZING", "CARD_GENERATING", "COMPLETED"))),
                "required", List.of("target"),
                "additionalProperties", false);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            if (param == null || param.getRuntimeContext() == null) {
                throw new BusinessException("状态推进工具缺少 RuntimeContext");
            }
            LearningTransitionIntent intent = param.getRuntimeContext().get(LearningTransitionIntent.class);
            if (intent == null) {
                throw new BusinessException("状态推进工具缺少服务端 transition intent");
            }
            Object rawTarget = param.getInput() == null ? null : param.getInput().get("target");
            if (!(rawTarget instanceof String target)) {
                throw new BusinessException("状态推进 target 不能为空");
            }
            KnowledgePointStatus status;
            try {
                status = KnowledgePointStatus.valueOf(target);
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("未知知识点目标状态: " + target);
            }
            intent.request(status);
            ToolUseBlock use = param.getToolUseBlock();
            return ToolResultBlock.of(
                    use == null ? null : use.getId(),
                    TOOL_NAME,
                    TextBlock.builder().text("{\"accepted\":true,\"target\":\"" + status + "\"}").build());
        });
    }
}
