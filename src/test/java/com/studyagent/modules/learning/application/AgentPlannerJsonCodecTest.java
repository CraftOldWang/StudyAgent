package com.studyagent.modules.learning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class AgentPlannerJsonCodecTest {

    private final AgentPlannerJsonCodec codec = new AgentPlannerJsonCodec(new ObjectMapper());

    @Test
    void parseDecisionShouldNormalizeLegalJson() {
        AgentPlannerDecision decision = codec.parseDecision("""
                {
                  "phase": "teach",
                  "currentTopicStatus": "needs_user_input",
                  "nextAction": "wait_user",
                  "responsePlan": "先讲 volatile 的可见性，再给一个小例子。",
                  "reason": "刚完成首次讲解，等待用户反馈"
                }
                """);

        assertThat(decision.phase()).isEqualTo("TEACH");
        assertThat(decision.currentTopicStatus()).isEqualTo("NEEDS_USER_INPUT");
        assertThat(decision.nextAction()).isEqualTo("WAIT_USER");
        assertThat(decision.responsePlan()).contains("volatile");
    }

    @Test
    void parseDecisionShouldRejectIllegalPhase() {
        assertThatThrownBy(() -> codec.parseDecision("""
                {
                  "phase": "PLAN",
                  "currentTopicStatus": "IN_PROGRESS",
                  "nextAction": "WAIT_USER",
                  "responsePlan": "继续学习"
                }
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法 Agent phase");
    }

    @Test
    void parseDecisionShouldRejectDoneWithoutSummary() {
        assertThatThrownBy(() -> codec.parseDecision("""
                {
                  "phase": "SUMMARY",
                  "currentTopicStatus": "DONE",
                  "nextAction": "MOVE_NEXT_TOPIC",
                  "responsePlan": "总结当前知识点",
                  "summary": ""
                }
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void parseDecisionShouldRejectCardAsStandalonePhase() {
        assertThatThrownBy(() -> codec.parseDecision("""
                {
                  "phase": "CARD",
                  "currentTopicStatus": "IN_PROGRESS",
                  "nextAction": "WAIT_USER",
                  "responsePlan": "说明已写入复习卡"
                }
                """))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法 Agent phase");
    }
}
