package com.studyagent.agent.web;

import com.studyagent.agent.integration.AgentHelloService;
import com.studyagent.common.response.ApiResponse;
import com.studyagent.identity.CurrentUserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentHelloController {

    private final AgentHelloService agentHelloService;
    private final CurrentUserContext currentUserContext;

    @PostMapping(value = "/hello", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<String> hello() {
        return ApiResponse.ok(agentHelloService.hello(currentUserContext.userId()));
    }
}
