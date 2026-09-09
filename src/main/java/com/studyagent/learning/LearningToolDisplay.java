package com.studyagent.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LearningToolDisplay {
    private final LearningTraceService traces;
    private final ObjectMapper mapper;

    public static JsonNode input(ObjectMapper mapper, String name, Object input) {
        JsonNode node = mapper.valueToTree(input);
        if ("learning_quiz_publish".equals(name) && node != null) {
            for (JsonNode question : node.path("questions")) {
                if (question instanceof ObjectNode object) { object.remove(List.of("correctAnswer", "explanation")); }
            }
        }
        return node;
    }

    public List<Map<String, Object>> history(Long userId, String traceId) {
        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        for (var event : traces.find(userId, traceId)) {
            if (!"TOOL".equals(event.getStage()) || event.getToolCallId() == null) { continue; }
            var call = calls.computeIfAbsent(event.getToolCallId(), id -> new LinkedHashMap<>(Map.of("id", id)));
            if ("TOOL_CALL".equals(event.getEventType())) {
                call.put("name", event.getSummary());
                call.put("input", input(mapper, event.getSummary(), parse(event.getPayloadJson())));
            } else {
                call.put("output", parse(event.getPayloadJson()));
                if ("FAILED".equals(event.getStatus())) { call.put("error", event.getSummary()); }
            }
            call.put("status", event.getStatus());
        }
        return List.copyOf(calls.values());
    }

    private JsonNode parse(String json) {
        try { return json == null ? mapper.nullNode() : mapper.readTree(json); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("工具记录无法读取", e); }
    }
}
