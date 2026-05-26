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

@Service
@RequiredArgsConstructor
public class ContextMemoryService {

    private final ChatContextSnapshotMapper snapshotMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatGenerationService chatGenerationService;

    public ChatContextSnapshot compressAfterRound(Long sessionId, Long coveredMessageId, String roundSummary) {
        if (sessionId == null || coveredMessageId == null) {
            throw new BusinessException("上下文压缩缺少会话或消息范围");
        }
        ChatContextSnapshot latest = snapshotMapper.selectLatest(sessionId);
        String previousSummary = latest == null ? "" : latest.getSummaryContent();
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

    public RestoredContext restore(Long sessionId) {
        ChatContextSnapshot latest = snapshotMapper.selectLatest(sessionId);
        Long coveredMessageId = latest == null ? 0L : latest.getCoveredMessageId();
        List<ChatMessage> messages = chatMessageMapper.selectAfter(sessionId, coveredMessageId);
        return new RestoredContext(latest, messages);
    }

    private int estimateTokenCount(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, content.length() / 2);
    }

    public record RestoredContext(
            ChatContextSnapshot snapshot,
            List<ChatMessage> messages
    ) {
    }
}
