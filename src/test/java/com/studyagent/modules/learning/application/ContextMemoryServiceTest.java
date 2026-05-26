package com.studyagent.modules.learning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.studyagent.infrastructure.ai.ChatGenerationService;
import com.studyagent.modules.learning.domain.ChatContextSnapshot;
import com.studyagent.modules.learning.domain.ChatMessage;
import com.studyagent.modules.learning.infrastructure.ChatContextSnapshotMapper;
import com.studyagent.modules.learning.infrastructure.ChatMessageMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContextMemoryServiceTest {

    @Mock
    private ChatContextSnapshotMapper snapshotMapper;

    @Mock
    private ChatMessageMapper chatMessageMapper;

    @Mock
    private ChatGenerationService chatGenerationService;

    @InjectMocks
    private ContextMemoryService contextMemoryService;

    @Test
    void restoreShouldLoadLatestSnapshotThenMessagesAfterCoveredId() {
        ChatContextSnapshot snapshot = new ChatContextSnapshot();
        snapshot.setId(10L);
        snapshot.setSessionId(1L);
        snapshot.setCoveredMessageId(99L);
        snapshot.setSummaryContent("压缩记忆");
        snapshot.setTokenCount(4);
        snapshot.setCreatedAt(LocalDateTime.now());
        ChatMessage message = new ChatMessage();
        message.setId(100L);
        message.setSessionId(1L);

        when(snapshotMapper.selectLatest(1L)).thenReturn(snapshot);
        when(chatMessageMapper.selectAfter(1L, 99L)).thenReturn(List.of(message));

        ContextMemoryService.RestoredContext restoredContext = contextMemoryService.restore(1L);

        assertThat(restoredContext.snapshot()).isSameAs(snapshot);
        assertThat(restoredContext.messages()).containsExactly(message);
    }
}
