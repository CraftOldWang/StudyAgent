package com.studyagent.learning;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.LearningPlanningProperties;
import com.studyagent.mapper.LearningPlanRunMapper;
import com.studyagent.mapper.LearningPlanStageMapper;
import com.studyagent.model.LearningPlanRun;
import com.studyagent.model.LearningPlanStage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlanningPersistence {
    private final LearningPlanRunMapper runs;
    private final LearningPlanStageMapper stages;
    private final LearningPlanningProperties properties;

    public LearningPlanRun require(Long userId, Long id) {
        LearningPlanRun run = runs.selectOne(Wrappers.<LearningPlanRun>query().eq("id", id).eq("user_id", userId));
        if (run == null) { throw new BusinessException(404, "规划任务不存在"); }
        return run;
    }

    public LearningPlanRun create(Long userId, Long kbId, String goal, String inputJson) {
        LearningPlanRun run = new LearningPlanRun();
        run.setUserId(userId);
        run.setKnowledgeBaseId(kbId);
        run.setLearningGoal(goal);
        run.setInputJson(inputJson);
        run.setStatus("READY");
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(run.getCreatedAt());
        runs.insert(run);
        return run;
    }

    public String claim(LearningPlanRun run) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int updated = runs.update(null, Wrappers.<LearningPlanRun>update().eq("id", run.getId())
                .eq("user_id", run.getUserId()).isNull("session_id")
                .and(q -> q.in("status", "READY", "FAILED").or()
                        .eq("status", "RUNNING").le("lease_until", now))
                .set("status", "RUNNING").set("processing_token", token)
                .set("lease_until", now.plusSeconds(properties.leaseSeconds()))
                .set("error_message", null).set("updated_at", now));
        if (updated != 1) { throw new BusinessException(409, "规划正在执行或已完成，请查询原任务"); }
        return token;
    }

    public List<LearningPlanStage> listStages(Long runId) {
        return stages.selectList(Wrappers.<LearningPlanStage>query().eq("run_id", runId).orderByAsc("id"));
    }

    public LearningPlanStage latest(Long runId, String key) {
        return stages.selectOne(Wrappers.<LearningPlanStage>query().eq("run_id", runId).eq("stage_key", key)
                .orderByDesc("attempt_count").last("LIMIT 1"));
    }

    @Transactional
    public LearningPlanStage begin(LearningPlanRun run, String token, String key, String hash, String input, String traceId) {
        fence(run.getId(), token);
        LearningPlanStage previous = latest(run.getId(), key);
        LearningPlanStage stage = new LearningPlanStage();
        stage.setRunId(run.getId());
        stage.setUserId(run.getUserId());
        stage.setStageKey(key);
        stage.setInputHash(hash);
        stage.setInputJson(input);
        stage.setTraceId(traceId);
        stage.setAttemptCount(previous == null ? 1 : previous.getAttemptCount() + 1);
        stage.setStatus("RUNNING");
        stage.setStartedAt(LocalDateTime.now());
        stages.insert(stage);
        return stage;
    }

    @Transactional
    public void finish(LearningPlanStage stage, String token) {
        fence(stage.getRunId(), token);
        stages.updateById(stage);
    }

    @Transactional
    public void complete(LearningPlanRun run, String token) {
        fence(run.getId(), token);
        runs.update(null, Wrappers.<LearningPlanRun>update().eq("id", run.getId())
                .set("status", "SUCCEEDED").set("processing_token", null)
                .set("lease_until", null).set("error_message", null).set("updated_at", LocalDateTime.now()));
    }

    public void fail(LearningPlanRun run, String token, String error) {
        runs.update(null, Wrappers.<LearningPlanRun>update().eq("id", run.getId()).eq("processing_token", token)
                .set("status", "FAILED").set("processing_token", null).set("lease_until", null)
                .set("error_message", error).set("updated_at", LocalDateTime.now()));
    }

    private void fence(Long runId, String token) {
        LocalDateTime now = LocalDateTime.now();
        int updated = runs.update(null, Wrappers.<LearningPlanRun>update().eq("id", runId)
                .eq("processing_token", token).gt("lease_until", now).eq("status", "RUNNING")
                .set("lease_until", now.plusSeconds(properties.leaseSeconds())).set("updated_at", now));
        if (updated != 1) { throw new BusinessException(409, "规划执行权已失效，请恢复原任务"); }
    }
}
