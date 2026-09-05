package com.studyagent.agent.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.model.ReviewCard;
import com.studyagent.review.ReviewCardService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * AgentScope 复习卡写入工具；模型只能提交卡片内容和来源 chunk。
 */
@Component
@RequiredArgsConstructor
public final class ReviewCardWriteTool implements AgentTool {

    public static final String TOOL_NAME = "review_card_write";

    private static final Map<String, Object> PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(
                    "drafts", Map.of(
                            "type", "array",
                            "description", "要写入的复习卡草稿，最多 5 张",
                            "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "front", Map.of("type", "string"),
                                            "back", Map.of("type", "string"),
                                            "sourceChunkId", Map.of("type", "string")),
                                    "required", List.of("front", "back"),
                                    "additionalProperties", false))),
            "required", List.of("drafts"),
            "additionalProperties", false);

    private final ReviewCardService reviewCardService;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "为当前知识点写入复习卡；front、back 必填，仅有可验证来源时提供 sourceChunkId。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return PARAMETERS;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            if (param == null) {
                throw new BusinessException("工具调用参数不能为空");
            }
            AgentInvocationScope scope = AgentInvocationScope.require(param.getRuntimeContext());
            List<CardDraft> drafts = parseDrafts(param.getInput());
            List<ReviewCard> cards = writeCards(scope, drafts);
            return result(param, cards);
        });
    }

    public List<ReviewCard> writeCards(AgentInvocationScope scope, List<CardDraft> drafts) {
        if (scope == null) {
            throw new BusinessException("Agent 调用 scope 不能为空");
        }
        if (drafts == null || drafts.isEmpty()) {
            throw new BusinessException("复习卡草稿不能为空");
        }
        return reviewCardService.writeBatch(
                scope.userId(),
                scope.knowledgePointId(),
                scope.knowledgeBaseId(),
                drafts.stream()
                        .map(draft -> new ReviewCardService.Draft(
                                draft.front(), draft.back(), draft.sourceChunkId()))
                        .toList());
    }

    private List<CardDraft> parseDrafts(Map<String, Object> input) {
        Object value = input == null ? null : input.get("drafts");
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new BusinessException("复习卡草稿不能为空");
        }
        List<CardDraft> drafts = new ArrayList<>(values.size());
        for (Object rawDraft : values) {
            if (!(rawDraft instanceof Map<?, ?> draft)) {
                throw new BusinessException("复习卡草稿格式错误");
            }
            drafts.add(new CardDraft(
                    requiredText(draft, "front", "复习卡正面不能为空"),
                    requiredText(draft, "back", "复习卡背面不能为空"),
                    optionalText(draft, "sourceChunkId")));
        }
        return drafts;
    }

    private String requiredText(Map<?, ?> input, String field, String message) {
        Object value = input.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new BusinessException(message);
        }
        return text;
    }

    private String optionalText(Map<?, ?> input, String field) {
        Object value = input.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new BusinessException("复习卡来源 chunk 格式错误");
        }
        return text.isBlank() ? null : text.trim();
    }

    private ToolResultBlock result(ToolCallParam param, List<ReviewCard> cards) {
        List<WrittenCard> writtenCards = cards.stream()
                .map(card -> new WrittenCard(
                        card.getId(),
                        card.getFront(),
                        card.getBack(),
                        card.getSourceChunkId()))
                .toList();
        String json = toJson(new ReviewCardWriteResult(writtenCards.size(), writtenCards));
        return ToolResultBlock.of(
                toolCallId(param),
                TOOL_NAME,
                TextBlock.builder().text(json).build());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("复习卡写入结果序列化失败: " + ex.getMessage());
        }
    }

    private String toolCallId(ToolCallParam param) {
        ToolUseBlock toolUseBlock = param.getToolUseBlock();
        return toolUseBlock == null ? null : toolUseBlock.getId();
    }

    public record CardDraft(
            String front,
            String back,
            String sourceChunkId
    ) {
    }

    public record ReviewCardWriteResult(
            int cardCount,
            List<WrittenCard> cards
    ) {
    }

    public record WrittenCard(
            Long id,
            String front,
            String back,
            String sourceChunkId
    ) {
    }
}
