package com.studyagent.infrastructure.ai;

public interface ChatGenerationService {

    String generate(String systemPrompt, String userPrompt);
}
