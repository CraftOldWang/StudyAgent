package com.studyagent.eval;

import com.studyagent.agent.integration.ModelCallScope;
import com.studyagent.common.exception.BusinessException;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("eval")
@RequiredArgsConstructor
public class EvaluationCompletionService {
    private final Model model;

    public Result complete(Long userId, String purpose, String promptVersion, String systemPrompt, String prompt, int maxTokens) {
        ModelCallScope incoming = ModelCallScope.current();
        if (incoming == null) { throw new BusinessException("评测调用缺少 trace scope"); }
        ModelCallScope scope = new ModelCallScope(incoming.traceId(), "EVAL/" + purpose + "/" + promptVersion);
        ReActAgent agent = ReActAgent.builder().name("evaluation")
                .model(model).sysPrompt(systemPrompt).maxIters(1)
                .generateOptions(GenerateOptions.builder().maxTokens(maxTokens).temperature(0.0).build()).build();
        try {
            var context = RuntimeContext.builder().userId(userId.toString()).sessionId(UUID.randomUUID().toString()).build();
            var response = agent.call(prompt, context).contextWrite(ctx -> ctx.put(ModelCallScope.class, scope)).block();
            if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
                throw new BusinessException("评测模型未返回有效文本");
            }
            return new Result(scope.traceId(), model.getModelName(), purpose, promptVersion, response.getTextContent());
        } finally {
            agent.close();
        }
    }

    public record Result(String traceId, String model, String purpose, String promptVersion, String text) { }
}
