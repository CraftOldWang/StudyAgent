package com.studyagent.modules.learning.application;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.infrastructure.ai.ChatGenerationService;
import com.studyagent.modules.learning.domain.ChatContextSnapshot;
import com.studyagent.modules.learning.domain.ChatMessage;
import com.studyagent.modules.learning.infrastructure.ChatContextSnapshotMapper;
import com.studyagent.modules.learning.infrastructure.ChatMessageMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 学习会话上下文记忆服务，负责压缩快照和会话恢复。
 *
 * <p>原始消息始终保存在 chat_messages，快照只记录覆盖到的 messageId 和压缩摘要。</p>
 */
@Service
@RequiredArgsConstructor
public class ContextMemoryService {

    private final ChatContextSnapshotMapper snapshotMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatGenerationService chatGenerationService;

    /**
     * 在一轮学习结束后生成新的压缩快照。
     */
    public ChatContextSnapshot compressAfterRound(Long sessionId, Long coveredMessageId, String roundSummary) {
        if (sessionId == null || coveredMessageId == null) {
            throw new BusinessException("上下文压缩缺少会话或消息范围");
        }
        ChatContextSnapshot latest = snapshotMapper.selectLatest(sessionId);
        String previousSummary = latest == null ? "" : latest.getSummaryContent();
        // 压缩失败不会删除原始消息，调用方会感知异常并保持可恢复状态。
        String compressed = chatGenerationService.generate(
                "你是学习会话的长期记忆压缩器。请保留本轮学习目标、已掌握知识点、仍需复习点、生成的复习卡主题。不要删除原始消息，只输出压缩记忆。",
                "上一轮压缩记忆：\n" + previousSummary + "\n\n本轮总结：\n" + roundSummary
        );

        ChatContextSnapshot snapshot = new ChatContextSnapshot();
        snapshot.setSessionId(sessionId);
        snapshot.setCoveredMessageId(coveredMessageId);
        snapshot.setSummaryContent(compressed);
        snapshot.setTokenCount(estimateTokenCount(compressed));
        snapshot.setCreatedAt(LocalDateTime.now());
        snapshotMapper.insert(snapshot);
        return snapshot;
    }

    /**
     * 恢复会话上下文：最近快照加快照之后的增量消息。
     */
    public RestoredContext restore(Long sessionId) {
        ChatContextSnapshot latest = snapshotMapper.selectLatest(sessionId);
        Long coveredMessageId = latest == null ? 0L : latest.getCoveredMessageId();
        List<ChatMessage> messages = chatMessageMapper.selectAfter(sessionId, coveredMessageId);
        return new RestoredContext(latest, messages);
    }

    /**
     * 估算压缩摘要 token 数，用于后续上下文预算。
     */
    private int estimateTokenCount(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, content.length() / 2);
    }

    /**
     * 恢复后的上下文载体。
     */
    public record RestoredContext(
            ChatContextSnapshot snapshot,
            List<ChatMessage> messages
    ) {
    }
}
