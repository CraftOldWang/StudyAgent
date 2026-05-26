package com.studyagent.infrastructure.ai;

import com.studyagent.common.exception.BusinessException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SpringAiChatGenerationService implements ChatGenerationService {

    private final ChatModel chatModel;

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        ChatResponse response = chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        )));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new BusinessException("模型未返回有效回答");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new BusinessException("模型回答为空");
        }
        return text.trim();
    }
}
