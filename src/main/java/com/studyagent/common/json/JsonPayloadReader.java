package com.studyagent.common.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsonPayloadReader {

    private final ObjectMapper objectMapper;

    public JsonNode readArray(String raw) throws IOException {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < start) {
            throw new IOException("未找到 JSON 数组");
        }
        return objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(raw.substring(start, end + 1));
    }
}
