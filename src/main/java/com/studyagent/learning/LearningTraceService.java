package com.studyagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.AgentTraceEventMapper;
import com.studyagent.model.AgentTraceEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LearningTraceService {

    private final AgentTraceEventMapper traceEventMapper;

    public String start() {
        return UUID.randomUUID().toString();
    }

    public void record(
            Long userId,
            String traceId,
            Long sessionId,
            String stage,
            String eventType,
            String summary,
            String status) {
        Long count = traceEventMapper.selectCount(new LambdaQueryWrapper<AgentTraceEvent>()
                .eq(AgentTraceEvent::getTraceId, traceId));
        AgentTraceEvent event = new AgentTraceEvent();
        event.setUserId(userId);
        event.setTraceId(traceId);
        event.setSessionId(sessionId);
        event.setSequenceNo(Math.toIntExact(count == null ? 1L : count + 1L));
        event.setStage(requireText(stage, "trace stage 不能为空"));
        event.setEventType(requireText(eventType, "trace eventType 不能为空"));
        event.setSummary(truncate(requireText(summary, "trace summary 不能为空"), 512));
        event.setStatus(requireText(status, "trace status 不能为空"));
        event.setCreatedAt(LocalDateTime.now());
        traceEventMapper.insert(event);
    }

    public List<AgentTraceEvent> find(Long userId, String traceId) {
        if (userId == null || traceId == null || traceId.isBlank()) {
            throw new BusinessException("trace 查询参数不能为空");
        }
        List<AgentTraceEvent> events = traceEventMapper.selectList(new LambdaQueryWrapper<AgentTraceEvent>()
                .eq(AgentTraceEvent::getUserId, userId)
                .eq(AgentTraceEvent::getTraceId, traceId)
                .orderByAsc(AgentTraceEvent::getSequenceNo));
        if (events.isEmpty()) {
            throw new BusinessException(404, "trace 不存在: " + traceId);
        }
        return events;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }
}
