package com.studyagent.learning.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.config.JacksonConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class LearningRestContractTest {

    @Test
    void serializesIdentifiersAsStringsAndCountersAsNumbers() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        builder.serializationInclusion(JsonInclude.Include.NON_NULL);
        new JacksonConfig().longToStringCustomizer().customize(builder);
        ObjectMapper mapper = builder.build();
        LearningSessionResponse response = new LearningSessionResponse(
                9007199254740993L,
                "Java",
                2L,
                "ACTIVE",
                null,
                null,
                List.of(new LearningSessionResponse.KnowledgePointResponse(
                        3L, 1, "Generics", List.of("bounds"), 20, "NEW", null, null)),
                null,
                List.of(new LearningSessionResponse.CardResponse(4L, "front", "back", null)));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertThat(json.get("id").isTextual()).isTrue();
        assertThat(json.get("id").asText()).isEqualTo("9007199254740993");
        assertThat(json.get("knowledgeBaseId").asText()).isEqualTo("2");
        assertThat(json.get("errorMessage").isNull()).isTrue();
        assertThat(json.get("activeKnowledgePoint").isNull()).isTrue();
        assertThat(json.get("currentQuiz").isNull()).isTrue();
        assertThat(json.at("/plan/0/id").asText()).isEqualTo("3");
        assertThat(json.at("/plan/0/sequenceNo").isInt()).isTrue();
        assertThat(json.at("/plan/0/explanation").isNull()).isTrue();
        assertThat(json.at("/cards/0/id").asText()).isEqualTo("4");
        assertThat(json.at("/cards/0/sourceChunkId").isNull()).isTrue();
    }
}
