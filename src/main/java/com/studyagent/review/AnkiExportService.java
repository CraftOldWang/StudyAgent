package com.studyagent.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AnkiProperties;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.ReviewCardMapper;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.ReviewCard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@RequiredArgsConstructor
public class AnkiExportService {
    private static final List<String> FIELDS = List.of("StudyPilotId", "Front", "Back", "Source");
    private final ReviewCardMapper cards;
    private final DocumentChunkMapper chunks;
    private final DocumentMapper documents;
    private final AnkiConnectClient anki;
    private final AnkiProperties properties;
    private final RedissonClient redis;

    public ExportStatus status(Long userId, Long cardId) {
        return view(requireCard(userId, cardId));
    }

    public ExportStatus export(Long userId, Long cardId) {
        requireCard(userId, cardId);
        var lock = redis.getLock("anki:card:" + userId + ":" + cardId);
        lock.lock();
        try {
            var card = requireCard(userId, cardId);
            cards.beginExport(userId, cardId);
            try {
                String source = source(card);
                ensureModel();
                String key = "studypilot-u" + userId + "-c" + cardId;
                Long noteId = findExisting(key);
                if (noteId == null) {
                    String deck = properties.deckPrefix() + "::知识库 " + card.getKnowledgeBaseId();
                    anki.call("createDeck", Map.of("deck", deck));
                    JsonNode added = anki.call("addNote", Map.of("note", Map.of(
                            "deckName", deck, "modelName", properties.modelName(),
                            "fields", Map.of("StudyPilotId", key, "Front", html(card.getFront()),
                                    "Back", html(card.getBack()), "Source", html(source)),
                            "options", Map.of("allowDuplicate", false), "tags", List.of("StudyPilot"))));
                    if (!added.isIntegralNumber() || added.asLong() <= 0) {
                        throw new BusinessException("Anki 未返回有效笔记 ID，可重试查询创建结果");
                    }
                    noteId = added.asLong();
                }
                cards.exportSucceeded(userId, cardId, noteId, LocalDateTime.now());
            } catch (BusinessException e) {
                // Anki 成功但响应丢失时保留失败；下一次先查稳定标识，不能盲目再创建。
                String message = e.getMessage();
                cards.exportFailed(userId, cardId, message.substring(0, Math.min(2048, message.length())));
                throw e;
            }
            return view(requireCard(userId, cardId));
        } finally {
            lock.unlock();
        }
    }

    private Long findExisting(String key) {
        JsonNode found = anki.call("findNotes", Map.of("query", "StudyPilotId:" + key));
        if (!found.isArray() || found.size() > 1) {
            throw new BusinessException("Anki 稳定标识查询异常或存在多张重复笔记，请检查后重试");
        }
        if (found.isEmpty()) return null;
        long id = found.get(0).asLong();
        JsonNode info = anki.call("notesInfo", Map.of("notes", List.of(com.fasterxml.jackson.databind.node.LongNode.valueOf(id))));
        if (!info.isArray() || info.size() != 1
                || !properties.modelName().equals(info.get(0).path("modelName").asText())
                || !key.equals(info.get(0).path("fields").path("StudyPilotId").path("value").asText())
                || info.get(0).path("cards").isEmpty()) {
            throw new BusinessException("Anki 已有笔记与导出标识或卡片模板不一致");
        }
        return id;
    }

    private void ensureModel() {
        var lock = redis.getLock("anki:model:" + properties.modelName());
        lock.lock();
        try {
            JsonNode names = anki.call("modelNames", Map.of());
            boolean exists = false;
            for (JsonNode name : names) if (properties.modelName().equals(name.asText())) exists = true;
            if (!exists) {
                anki.call("createModel", Map.of("modelName", properties.modelName(), "inOrderFields", FIELDS,
                        "css", ".card { font-family: sans-serif; font-size: 22px; text-align: left; line-height: 1.6; } .source { font-size: 14px; opacity: .7; margin-top: 24px; overflow-wrap: anywhere; }",
                        "cardTemplates", List.of(Map.of("Name", "复习", "Front", "{{Front}}",
                                "Back", "{{FrontSide}}<hr id=answer>{{Back}}<div class=source>{{Source}}</div>"))));
            }
            JsonNode fields = anki.call("modelFieldNames", Map.of("modelName", properties.modelName()));
            if (!fields.isArray() || fields.size() != FIELDS.size()) {
                throw new BusinessException("Anki StudyPilot 模板字段不匹配，请检查模板");
            }
            for (int i = 0; i < FIELDS.size(); i++) {
                if (!FIELDS.get(i).equals(fields.get(i).asText())) {
                    throw new BusinessException("Anki StudyPilot 模板字段顺序不匹配，请检查模板");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private ReviewCard requireCard(Long userId, Long cardId) {
        var card = cards.selectById(cardId);
        if (card == null || !Objects.equals(userId, card.getUserId())) {
            throw new BusinessException("复习卡不存在或不属于当前用户");
        }
        return card;
    }

    private String source(ReviewCard card) {
        String reference = "StudyPilot · 知识库 " + card.getKnowledgeBaseId() + " · 卡片 " + card.getId();
        if (card.getSourceChunkId() == null || card.getSourceChunkId().isBlank()) return reference + "\n未关联资料来源";
        var chunk = chunks.selectOne(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getChunkId, card.getSourceChunkId()));
        var doc = chunk == null ? null : documents.selectById(chunk.getDocumentId());
        if (doc == null || !Objects.equals(doc.getUserId(), card.getUserId())
                || !Objects.equals(doc.getKnowledgeBaseId(), card.getKnowledgeBaseId())) {
            throw new BusinessException("复习卡来源不存在或归属不符，未导出");
        }
        return reference + "\n" + doc.getTitle() + "\nchunk: " + chunk.getChunkId()
                + (chunk.getSourceLocation() == null ? "" : "\n" + chunk.getSourceLocation());
    }

    static String html(String text) {
        return HtmlUtils.htmlEscape(text).replace("\r\n", "\n").replace("\n", "<br>");
    }

    private ExportStatus view(ReviewCard card) {
        return new ExportStatus(card.getId(), card.getAnkiExportStatus(), card.getAnkiNoteId(),
                card.getAnkiExportError(), card.getAnkiExportAttempts(), card.getAnkiExportedAt());
    }

    public record ExportStatus(Long cardId, String status, Long noteId, String errorMessage,
                               Integer attempts, LocalDateTime exportedAt) {}
}
