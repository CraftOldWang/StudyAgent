package com.studyagent.agent.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.studyagent.agent.integration.AgentHelloService;
import com.studyagent.identity.CurrentUserContext;
import com.studyagent.identity.IdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AgentHelloController.class)
@ContextConfiguration(classes = AgentHelloController.class)
@Import({IdentityResolver.class, CurrentUserContext.class})
class AgentHelloControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentHelloService agentHelloService;

    @Test
    void mapsPostHeaderAndUnifiedResponseWithoutRequestBody() throws Exception {
        when(agentHelloService.hello(42L)).thenReturn("hello from model");

        mockMvc.perform(post("/api/agent/hello")
                        .header("X-User-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data").value("hello from model"));

        verify(agentHelloService).hello(42L);
    }
}
