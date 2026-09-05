package com.studyagent.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.skill.WorkspaceSkillRepository;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LearningSkillTemplateTest {

    private static final Path WORKSPACE = Path.of(".agentscope", "workspace");

    @Test
    void agentScopeRepositoryLoadsAllLearningSkills() {
        Map<String, AgentSkill> skills = loadSkills();

        assertThat(skills).containsKeys("explain", "quiz", "card");
        assertThat(skills.values())
                .allSatisfy(skill -> {
                    assertThat(skill.getDescription()).isNotBlank();
                    assertThat(skill.getSkillContent())
                            .contains("## Input schema", "## Output schema")
                            .isNotBlank();
                });
    }

    @Test
    void learningSkillsDeclareRequiredOutputContracts() {
        Map<String, AgentSkill> skills = loadSkills();

        assertThat(skills.get("explain").getSkillContent())
                .contains("knowledgePointId", "readable explanation", "concrete example", "source chunk ids");
        assertThat(skills.get("quiz").getSkillContent())
                .contains("exactly 5", "question", "options", "correctAnswer", "explanation", "sourceChunkId");
        assertThat(skills.get("card").getSkillContent())
                .contains("exactly 3", "front", "back", "sourceChunkId", "null");
    }

    private Map<String, AgentSkill> loadSkills() {
        WorkspaceSkillRepository repository = new WorkspaceSkillRepository(
                new LocalFilesystem(WORKSPACE),
                "skills",
                RuntimeContext::empty);
        return repository.getAllSkills().stream()
                .collect(Collectors.toMap(AgentSkill::getName, Function.identity()));
    }
}
