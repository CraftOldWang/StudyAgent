package com.studyagent.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AnkiProperties;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.ReviewCardMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.ReviewCard;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class AnkiExportServiceTest {
    final ReviewCardMapper cards = mock(ReviewCardMapper.class);
    final DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
    final DocumentMapper documents = mock(DocumentMapper.class);
    final AnkiConnectClient client = mock(AnkiConnectClient.class);
    final RedissonClient redis = mock(RedissonClient.class);
    final ObjectMapper json = new ObjectMapper();
    final ReviewCard card = new ReviewCard();
    AnkiExportService service;

    @BeforeEach void setup() {
        card.setId(3L); card.setUserId(1L); card.setKnowledgeBaseId(2L);
        card.setFront("x < y\n问题"); card.setBack("<script>answer</script>");
        when(cards.selectById(3L)).thenReturn(card);
        when(redis.getLock(anyString())).thenReturn(mock(RLock.class));
        service = new AnkiExportService(cards, chunks, documents, client,
                new AnkiProperties("http://localhost:8765", Duration.ofSeconds(2), "StudyPilot v1", "StudyPilot"), redis);
    }

    void ready() throws Exception {
        when(client.call(eq("modelNames"), anyMap())).thenReturn(json.readTree("[\"StudyPilot v1\"]"));
        when(client.call(eq("modelFieldNames"), anyMap())).thenReturn(json.readTree("[\"StudyPilotId\",\"Front\",\"Back\",\"Source\"]"));
    }

    @Test void sourceOwnershipIsCheckedBeforeAnyAnkiCall() {
        card.setSourceChunkId("source");
        var chunk = new DocumentChunk(); chunk.setDocumentId(9L);
        when(chunks.selectOne(any())).thenReturn(chunk);
        var document = new Document(); document.setUserId(7L); document.setKnowledgeBaseId(2L);
        when(documents.selectById(9L)).thenReturn(document);
        assertThatThrownBy(() -> service.export(1L, 3L)).hasMessageContaining("归属");
        verifyNoInteractions(client);
        verify(cards).exportFailed(eq(1L), eq(3L), contains("归属"));
    }

    @Test void rejectsOtherUserBeforeExportMutation() {
        assertThatThrownBy(() -> service.export(8L, 3L)).hasMessageContaining("不属于");
        verify(cards, never()).beginExport(anyLong(), anyLong());
        verifyNoInteractions(client);
    }

    @Test void reusesNoteAfterLostResponseWithoutAddingAgain() throws Exception {
        ready();
        when(client.call(eq("findNotes"), anyMap())).thenReturn(json.readTree("[1789000000000]"));
        when(client.call(eq("notesInfo"), anyMap())).thenReturn(json.readTree("""
                [{"noteId":1789000000000,"modelName":"StudyPilot v1","cards":[1789000000001],
                "fields":{"StudyPilotId":{"value":"studypilot-u1-c3"}}}]
                """));
        service.export(1L, 3L);
        verify(client, never()).call(eq("addNote"), anyMap());
        verify(cards).exportSucceeded(eq(1L), eq(3L), eq(1789000000000L), any());
    }

    @Test void timeoutIsDurableAndRetryFindsNoteInsteadOfBlindResend() throws Exception {
        ready();
        when(client.call(eq("findNotes"), anyMap())).thenReturn(json.readTree("[]"), json.readTree("[77]"));
        when(client.call(eq("addNote"), anyMap())).thenThrow(new BusinessException("响应丢失"));
        when(client.call(eq("notesInfo"), anyMap())).thenReturn(json.readTree("""
                [{"modelName":"StudyPilot v1","cards":[88],"fields":{"StudyPilotId":{"value":"studypilot-u1-c3"}}}]
                """));
        assertThatThrownBy(() -> service.export(1L, 3L)).hasMessage("响应丢失");
        verify(cards).exportFailed(1L, 3L, "响应丢失");
        service.export(1L, 3L);
        verify(client, times(1)).call(eq("addNote"), anyMap());
        verify(cards).exportSucceeded(eq(1L), eq(3L), eq(77L), any());
    }

    @Test void exportsEscapedContentAndStableFirstField() throws Exception {
        ready();
        when(client.call(eq("findNotes"), anyMap())).thenReturn(json.readTree("[]"));
        when(client.call(eq("addNote"), anyMap())).thenReturn(json.readTree("77"));
        service.export(1L, 3L);
        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(client).call(eq("addNote"), payload.capture());
        var note = json.valueToTree(payload.getValue()).path("note");
        assertThat(note.path("fields").path("Front").asText()).isEqualTo("x &lt; y<br>问题");
        assertThat(note.path("fields").path("Back").asText()).doesNotContain("<script>");
        assertThat(note.path("fields").path("StudyPilotId").asText()).isEqualTo("studypilot-u1-c3");
        assertThat(note.path("fields").path("Source").asText()).contains("未关联资料来源");
        assertThat(note.path("options").path("allowDuplicate").asBoolean()).isFalse();
    }

    @Test void mismatchedExistingModelDoesNotOverwriteUserTemplate() throws Exception {
        ready();
        when(client.call(eq("modelFieldNames"), anyMap())).thenReturn(json.readTree("[\"Front\",\"Back\"]"));
        assertThatThrownBy(() -> service.export(1L, 3L)).hasMessageContaining("字段不匹配");
        verify(client, never()).call(eq("createModel"), anyMap());
        verify(client, never()).call(eq("addNote"), anyMap());
    }
}
