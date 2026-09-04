package com.studyagent.agent.integration;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentHelloService {

    private final HarnessAgent harnessAgent;

    public String hello(Long userId) {
        String sessionId = UUID.randomUUID().toString();
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId.toString())
                .sessionId(sessionId)
                .build();

        log.info("调用最简 Agent: userId={}, sessionId={}", userId, sessionId);
        Msg response = harnessAgent.call("hello", context).block();
        return response.getTextContent();
    }
}
