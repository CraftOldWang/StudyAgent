package com.studyagent.ingest.pipeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.config.AiModelProperties;
import com.studyagent.mapper.EmbeddingArtifactMapper;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.EmbeddingArtifact;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import com.studyagent.rag.embedding.EmbeddingService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentEmbeddingArtifacts {
    private final EmbeddingArtifactMapper mapper;
    private final EmbeddingService embeddingService;
    private final AiModelProperties properties;
    private final ObjectMapper objectMapper;

    // Only DOCUMENT embeddings are reusable here; QUERY calls retain their own measurement boundary.
    public float[] loadOrCreate(Long userId, DocumentChunk chunk) {
        EmbeddingArtifact cached = find(userId, chunk.getContentHash());
        if (cached != null) {
            log.info("复用 DOCUMENT embedding: documentId={}, chunkId={}", chunk.getDocumentId(), chunk.getChunkId());
            return decode(cached);
        }
        float[] vector = embeddingService.embed(chunk.getContent(), EmbeddingPurpose.DOCUMENT);
        validate(vector);
        EmbeddingArtifact artifact = new EmbeddingArtifact();
        artifact.setUserId(userId);
        artifact.setContentHash(chunk.getContentHash());
        artifact.setModel(properties.embedding().model());
        artifact.setDimensions(properties.embedding().dimensions());
        artifact.setCreatedAt(LocalDateTime.now());
        try {
            artifact.setVectorJson(objectMapper.writeValueAsString(vector));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化 embedding 产物", ex);
        }
        try {
            mapper.insert(artifact);
        } catch (DuplicateKeyException ex) {
            // Concurrent documents may finish the same immutable content; use the committed winner.
            EmbeddingArtifact winner = find(userId, chunk.getContentHash());
            if (winner == null) { throw ex; }
            return decode(winner);
        }
        return vector;
    }

    private EmbeddingArtifact find(Long userId, String hash) {
        return mapper.selectOne(Wrappers.<EmbeddingArtifact>lambdaQuery()
                .eq(EmbeddingArtifact::getUserId, userId)
                .eq(EmbeddingArtifact::getContentHash, hash)
                .eq(EmbeddingArtifact::getModel, properties.embedding().model())
                .eq(EmbeddingArtifact::getDimensions, properties.embedding().dimensions()));
    }

    private float[] decode(EmbeddingArtifact artifact) {
        try {
            float[] vector = objectMapper.readValue(artifact.getVectorJson(), float[].class);
            validate(vector);
            return vector;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("embedding 产物损坏: id=" + artifact.getId(), ex);
        }
    }

    private void validate(float[] vector) {
        if (vector == null || vector.length != properties.embedding().dimensions()) {
            throw new IllegalStateException("embedding 产物维度错误");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) { throw new IllegalStateException("embedding 产物包含非有限数值"); }
        }
    }
}
