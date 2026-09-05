package com.studyagent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.ReviewCardMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.ReviewCard;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class ReviewCardServiceTest {

    @Mock
    private ReviewCardMapper reviewCardMapper;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Test
    void writesCardsInOneTransactionWithExplicitServerScope() throws Exception {
        DocumentChunk firstChunk = chunk("chunk-1", 501L);
        DocumentChunk secondChunk = chunk("chunk-2", 502L);
        when(documentChunkMapper.selectOne(any())).thenReturn(firstChunk, secondChunk);
        when(documentMapper.selectById(501L)).thenReturn(document(11L, 22L));
        when(documentMapper.selectById(502L)).thenReturn(document(11L, 22L));
        ReviewCardService service = new ReviewCardService(
                reviewCardMapper, documentChunkMapper, documentMapper);

        List<ReviewCard> cards = service.writeBatch(
                11L,
                33L,
                22L,
                List.of(
                        new ReviewCardService.Draft("问题 1", "答案 1", "chunk-1"),
                        new ReviewCardService.Draft("问题 2", "答案 2", "chunk-2")));

        assertThat(cards).hasSize(2);
        assertThat(cards).allSatisfy(card -> {
            assertThat(card.getUserId()).isEqualTo(11L);
            assertThat(card.getKnowledgePointId()).isEqualTo(33L);
            assertThat(card.getKnowledgeBaseId()).isEqualTo(22L);
            assertThat(card.getExportedToAnki()).isFalse();
            assertThat(card.getCreatedAt()).isNotNull();
        });
        verify(reviewCardMapper, times(2)).insert(any(ReviewCard.class));

        Method method = ReviewCardService.class.getMethod(
                "writeBatch", Long.class, Long.class, Long.class, List.class);
        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void rejectsSourceDocumentOutsideCurrentUserAndKnowledgeBaseBeforeWriting() {
        when(documentChunkMapper.selectOne(any())).thenReturn(chunk("foreign", 501L));
        when(documentMapper.selectById(501L)).thenReturn(document(99L, 22L));
        ReviewCardService service = new ReviewCardService(
                reviewCardMapper, documentChunkMapper, documentMapper);

        assertThatThrownBy(() -> service.writeBatch(
                11L,
                33L,
                22L,
                List.of(new ReviewCardService.Draft("问题", "答案", "foreign"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前用户和知识库");

        verify(reviewCardMapper, never()).insert(any(ReviewCard.class));
    }

    @Test
    void storesNullWhenCardHasNoVerifiedSource() {
        ReviewCardService service = new ReviewCardService(reviewCardMapper, documentChunkMapper, documentMapper);

        List<ReviewCard> cards = service.writeBatch(
                11L, 33L, 22L, List.of(new ReviewCardService.Draft("问题", "答案", null)));

        assertThat(cards.getFirst().getSourceChunkId()).isNull();
        verify(reviewCardMapper).insert(cards.getFirst());
        verify(documentChunkMapper, never()).selectOne(any());
    }

    private DocumentChunk chunk(String chunkId, Long documentId) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkId(chunkId);
        chunk.setDocumentId(documentId);
        return chunk;
    }

    private Document document(Long userId, Long knowledgeBaseId) {
        Document document = new Document();
        document.setUserId(userId);
        document.setKnowledgeBaseId(knowledgeBaseId);
        return document;
    }
}
