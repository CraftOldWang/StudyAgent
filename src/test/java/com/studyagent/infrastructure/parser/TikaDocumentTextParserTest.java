package com.studyagent.infrastructure.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TikaDocumentTextParserTest {

    private final TikaDocumentTextParser parser = new TikaDocumentTextParser();

    @Test
    void parsePlainText() {
        ByteArrayInputStream inputStream = new ByteArrayInputStream("hello tika".getBytes(StandardCharsets.UTF_8));

        String text = parser.parse(inputStream);

        assertThat(text).contains("hello tika");
    }
}
