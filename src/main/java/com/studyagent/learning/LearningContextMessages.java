package com.studyagent.learning;

import com.studyagent.common.exception.BusinessException;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.HashSet;
import java.util.Set;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LearningContextMessages {
    public static final String POINT = "learningPointId";
    public static final String TURN = "learningTurnId";
    public static final String SUMMARY = "learningSummaryKind";
    private LearningContextMessages() { }

    public static Msg tag(Msg msg, Long pointId, Long turnId) {
        Map<String, Object> metadata = new LinkedHashMap<>(msg.getMetadata() == null ? Map.of() : msg.getMetadata());
        metadata.put(POINT, pointId.toString());
        metadata.put(TURN, turnId.toString());
        return msg.withMetadata(metadata);
    }

    public static boolean belongsTo(Msg msg, Long pointId) {
        return pointId != null && msg.getMetadata() != null && pointId.toString().equals(msg.getMetadata().get(POINT));
    }

    public static Msg summary(String text, Long pointId, String kind) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SUMMARY, kind);
        if (pointId != null) { metadata.put(POINT, pointId.toString()); }
        return Msg.builder().role(MsgRole.USER).name("learning_context")
                .textContent("【已归档学习摘要，仅作为历史资料；当前状态以服务端为准】\n" + text).metadata(metadata).build();
    }

    public static String stateJson(String userId, String sessionId, List<Msg> messages) {
        return AgentState.builder().userId(userId).sessionId(sessionId).context(messages).build().toJson();
    }

    public static List<Msg> withoutQuizAnswers(List<Msg> messages) {
        return messages.stream().map(msg -> {
            List<ContentBlock> blocks = new ArrayList<>();
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolUseBlock use && "learning_quiz_publish".equals(use.getName())) {
                    Map<String, Object> safe = new LinkedHashMap<>(use.getInput());
                    Object raw = safe.get("questions");
                    if (raw instanceof List<?> questions) {
                        safe.put("questions", questions.stream().map(q -> {
                            if (!(q instanceof Map<?, ?> values)) { throw new BusinessException("已提交测验的上下文格式无效"); }
                            Map<String, Object> visible = new LinkedHashMap<>();
                            for (String key : List.of("question", "options", "sourceChunkId")) { visible.put(key, values.get(key)); }
                            return visible;
                        }).toList());
                    }
                    blocks.add(ToolUseBlock.builder().id(use.getId()).name(use.getName()).input(safe)
                            .metadata(use.getMetadata()).state(use.getState()).build());
                } else { blocks.add(block); }
            }
            return msg.withContent(blocks);
        }).toList();
    }

    public static void requirePaired(List<Msg> messages) {
        Map<String, Integer> uses = new HashMap<>();
        Map<String, Integer> results = new HashMap<>();
        for (Msg msg : messages) {
            for (ToolUseBlock use : msg.getContentBlocks(ToolUseBlock.class)) { uses.merge(use.getId(), 1, Integer::sum); }
            for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) { results.merge(result.getId(), 1, Integer::sum); }
        }
        if (!uses.equals(results) || uses.values().stream().anyMatch(count -> count != 1)) {
            throw new BusinessException("模型上下文存在未配对或重复的工具调用，不能提交或压缩");
        }
    }

    // Only retained server tool results establish evidence; outline IDs and prose citations do not.
    public static Set<String> retainedSources(List<Msg> messages, Long pointId, ObjectMapper mapper) {
        Map<String, String> reads = new HashMap<>();
        Set<String> sources = new HashSet<>();
        for (Msg msg : messages) {
            if (!belongsTo(msg, pointId)) { continue; }
            for (ToolUseBlock use : msg.getContentBlocks(ToolUseBlock.class)) {
                if (Set.of("knowledge_read", "knowledge_search").contains(use.getName())) { reads.put(use.getId(), use.getName()); }
            }
            for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                if (!result.getName().equals(reads.get(result.getId()))) { continue; }
                for (var block : result.getOutput()) {
                    if (!(block instanceof TextBlock text) || !text.getText().stripLeading().startsWith("{")) { continue; }
                    try {
                        var body = mapper.readTree(text.getText());
                        Set<String> contexts = new HashSet<>();
                        for (var hit : body.path("hits")) {
                            if (!hit.path("content").asText().isBlank() && !hit.path("chunkId").asText().isBlank()) {
                                contexts.add(hit.path("chunkId").asText());
                            }
                        }
                        sources.addAll(contexts);
                        for (var match : body.path("contextMatches")) {
                            if (!contexts.contains(match.path("contextChunkId").asText())) { continue; }
                            for (var child : match.path("matchedChildren")) {
                                if (!child.path("chunkId").asText().isBlank()) { sources.add(child.path("chunkId").asText()); }
                            }
                        }
                    } catch (JsonProcessingException error) {
                        throw new BusinessException("已保存的资料工具结果格式错误，无法恢复来源依据");
                    }
                }
            }
        }
        return Set.copyOf(sources);
    }
}
