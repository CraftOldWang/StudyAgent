package com.studyagent.learning;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AgentScopeWorkspaceProperties;
import com.studyagent.model.KnowledgePoint;
import com.studyagent.model.LearningSession;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LearningContextInitializer {
    private final AgentScopeWorkspaceProperties workspace;

    public String initialState(LearningSession session, KnowledgePoint point) {
        var root = workspace.workspace().resolve("state/ReActAgent").toAbsolutePath().normalize();
        var file = root.resolve(session.getUserId().toString()).resolve(session.getAgentscopeSessionId()).resolve("agent_state.json").normalize();
        if (!file.startsWith(root)) { throw new BusinessException("旧版上下文路径无效"); }
        if (Files.exists(file)) {
            AgentState old;
            try { old = AgentState.fromJsonString(Files.readString(file)); }
            catch (IOException error) { throw new BusinessException("旧版上下文无法读取，原文件已保留"); }
            if (!session.getUserId().toString().equals(old.getUserId()) || !session.getAgentscopeSessionId().equals(old.getSessionId())) {
                throw new BusinessException("旧版上下文身份与学习会话不一致");
            }
            var messages = old.getContext().stream().filter(m -> m.getRole() != MsgRole.SYSTEM).toList();
            LearningContextMessages.requirePaired(messages);
            return LearningContextMessages.stateJson(old.getUserId(), old.getSessionId(), LearningContextMessages.withoutQuizAnswers(messages));
        }
        if (!KnowledgePointStatus.NEW.name().equals(point.getStatus()) || point.getSequenceNo() != 1) {
            throw new BusinessException("旧版学习会话缺少持久化上下文，不能假装恢复为空白会话；请恢复原状态文件或建立新计划");
        }
        return LearningContextMessages.stateJson(session.getUserId().toString(), session.getAgentscopeSessionId(), List.of());
    }
}
