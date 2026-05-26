package com.studyagent.infrastructure.parser;

import java.io.InputStream;

public interface DocumentTextParser {
    String parse(InputStream inputStream);
}
