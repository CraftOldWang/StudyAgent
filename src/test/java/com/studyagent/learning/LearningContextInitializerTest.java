package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import com.studyagent.config.AgentScopeWorkspaceProperties;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LearningContextInitializerTest {
    @Test
    void importsLegacyContextWithoutDeletingSourceAndRejectsMissingStartedSession(@TempDir Path root) throws Exception {
        var initializer = new LearningContextInitializer(new AgentScopeWorkspaceProperties(root));
        var session = new LearningSession(); session.setUserId(1L); session.setAgentscopeSessionId("session");
        var point = new KnowledgePoint(); point.setStatus("EXPLAINING"); point.setSequenceNo(1);
        assertThatThrownBy(() -> initializer.initialState(session, point)).hasMessageContaining("缺少持久化上下文");
        var file = root.resolve("state/ReActAgent/1/session/agent_state.json"); Files.createDirectories(file.getParent());
        String original = LearningContextMessages.stateJson("1", "session", List.of(Msg.builder().role(MsgRole.USER).textContent("synthetic prior context").build()));
        Files.writeString(file, original);
        var recovered = AgentState.fromJsonString(initializer.initialState(session, point));
        assertThat(recovered.getContext()).hasSize(1);
        assertThat(Files.readString(file)).isEqualTo(original);
        Files.writeString(file, "broken json");
        assertThatThrownBy(() -> initializer.initialState(session, point)).isInstanceOf(RuntimeException.class);
    }
}
