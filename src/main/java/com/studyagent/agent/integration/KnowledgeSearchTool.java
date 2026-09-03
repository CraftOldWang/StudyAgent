package com.studyagent.agent.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.governance.KnowledgeSearchRetryExecutor;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.RagProperties;
import com.studyagent.rag.retrieval.RetrievalHit;
import com.studyagent.rag.retrieval.RetrievalMode;
import com.studyagent.rag.retrieval.RetrievalService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * AgentScope 知识检索工具；权限范围只从服务端 typed scope 注入。
 */
@Component
@RequiredArgsConstructor
public final class KnowledgeSearchTool implements AgentTool {

    public static final String TOOL_NAME = "knowledge_search";

    private static final Map<String, Object> PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(
                    "query", Map.of(
                            "type", "string",
                            "description", "要在当前知识库中检索的学习问题或关键词")),
            "required", List.of("query"),
            "additionalProperties", false);

    private final RetrievalService retrievalService;
    private final KnowledgeSearchRetryExecutor retryExecutor;
    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "在服务端已验证的当前知识库范围内检索学习资料，只需提供 query。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return PARAMETERS;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            if (param == null) {
                throw new BusinessException("工具调用参数不能为空");
            }
            AgentInvocationScope scope = AgentInvocationScope.require(param.getRuntimeContext());
            String query = requiredText(param.getInput(), "query", "检索问题不能为空");
            return result(param, query, search(scope, query));
        });
    }

    public List<RetrievalHit> search(AgentInvocationScope scope, String query) {
        if (scope == null) {
            throw new BusinessException("Agent 调用 scope 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new BusinessException("检索问题不能为空");
        }
        return retryExecutor.execute(TOOL_NAME, () -> retrievalService.retrieve(
                RetrievalMode.BM25,
                scope.userId().toString(),
                scope.knowledgeBaseId().toString(),
                query,
                null,
                ragProperties.bm25CandidateSize(),
                ragProperties.topK()));
    }

    private ToolResultBlock result(ToolCallParam param, String query, List<RetrievalHit> hits) {
        List<KnowledgeSearchHit> outputHits = hits.stream()
                .map(hit -> new KnowledgeSearchHit(
                        hit.chunkId(),
                        hit.parentChunkId(),
                        hit.content(),
                        hit.score()))
                .toList();
        String json = toJson(new KnowledgeSearchResult(query, outputHits));
        ToolResultBlock result = ToolResultBlock.of(
                toolCallId(param),
                TOOL_NAME,
                TextBlock.builder().text(json).build());
        return result;
    }

    private String requiredText(Map<String, Object> input, String field, String message) {
        Object value = input == null ? null : input.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new BusinessException(message);
        }
        return text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("知识检索结果序列化失败: " + ex.getMessage());
        }
    }

    private String toolCallId(ToolCallParam param) {
        ToolUseBlock toolUseBlock = param.getToolUseBlock();
        return toolUseBlock == null ? null : toolUseBlock.getId();
    }

    public record KnowledgeSearchResult(
            String query,
            List<KnowledgeSearchHit> hits
    ) {
    }

    public record KnowledgeSearchHit(
            String chunkId,
            String parentChunkId,
            String content,
            double score
    ) {
    }
}
