package com.studyagent.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.integration.KnowledgeSearchExecution;
import com.studyagent.agent.integration.KnowledgeSearchScope;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.rag.retrieval.KnowledgeSearchResponse;
import com.studyagent.rag.retrieval.RetrievalHit;
import com.studyagent.rag.retrieval.SourceReader;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;

/** Read a planned source when semantic search cannot locate its original language. */
public final class LearningSourceTool implements AgentTool {
    private final SourceReader reader;
    private final Set<String> allowed;
    private final ObjectMapper mapper;

    public LearningSourceTool(SourceReader reader, List<String> sourceIds, ObjectMapper mapper) {
        this.reader = reader; this.allowed = Set.copyOf(sourceIds); this.mapper = mapper;
    }

    @Override public String getName() { return "knowledge_read"; }
    @Override public String getDescription() {
        return "按完整chunkId读取当前知识点计划中已确认的原文。已知来源时优先读取；只允许本知识点列出的ID。读取成功后可引用它讲解或出题。";
    }
    @Override public Map<String, Object> getParameters() {
        return Map.of("type", "object", "properties", Map.of("chunkId", Map.of("type", "string")),
                "required", List.of("chunkId"), "additionalProperties", false);
    }
    @Override public boolean isReadOnly() { return true; }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            var scope = KnowledgeSearchScope.require(param.getRuntimeContext());
            Object requested = param.getInput().get("chunkId");
            if (!(requested instanceof String id) || !allowed.contains(id)) {
                throw new BusinessException("只能读取当前知识点计划中列出的完整来源ID");
            }
            var execution = param.getRuntimeContext().get(KnowledgeSearchExecution.class);
            if (execution == null) { throw new BusinessException("缺少当前学习回合的资料读取记录"); }
            var source = reader.read(scope.userId(), scope.knowledgeBaseId(), id);
            var response = new KnowledgeSearchResponse("source:" + id, "按已确认的计划来源读取原文", List.of(
                    new KnowledgeSearchResponse.Result(source.chunkId(), source.content(),
                            new RetrievalHit.Provenance(source.documentId().toString(), source.documentTitle(), source.sourceLocation()), 1)));
            String body = mapper.writeValueAsString(response.modelView());
            execution.append(response);
            return ToolResultBlock.of(param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId(), getName(),
                    TextBlock.builder().text(body).build());
        });
    }
}
