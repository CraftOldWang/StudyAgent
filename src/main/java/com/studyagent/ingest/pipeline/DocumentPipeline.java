package com.studyagent.ingest.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.algo.chunk.ChunkSegment;
import com.studyagent.algo.chunk.SourceLocation;
import com.studyagent.algo.chunk.StructuredChunker;
import com.studyagent.algo.chunk.TokenWindowChunker;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.config.AiModelProperties;
import com.studyagent.ingest.parse.DocumentTextParser;
import com.studyagent.ingest.storage.ObjectStorageService;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.FileRecord;
import com.studyagent.rag.index.ElasticsearchChunkDocument;
import com.studyagent.rag.index.ElasticsearchIndexer;
import com.studyagent.rag.embedding.EmbeddingPurpose;
import com.studyagent.rag.embedding.EmbeddingService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DocumentPipeline {

    public static final String PARSER_VERSION = "tika-3.3.0";
    public static final String CHUNKER_VERSION = "structured-jtokkit-cl100k-v1";

    private final DocumentPipelinePersistence persistence;
    private final ObjectStorageService objectStorageService;
    private final DocumentTextParser documentTextParser;
    private final StructuredChunker structuredChunker;
    private final TokenWindowChunker tokenWindowChunker;
    private final EmbeddingService embeddingService;
    private final ElasticsearchIndexer elasticsearchIndexer;
    private final AiModelProperties aiModelProperties;
    private final ObjectMapper objectMapper;

    public boolean process(Long documentId) {
        return execute(documentId, true);
    }

    public boolean processPending(Long documentId) {
        return execute(documentId, false);
    }

    private boolean execute(Long documentId, boolean allowFailedRetry) {
        Document document = persistence.claim(documentId, allowFailedRetry);
        if (document == null) {
            return false;
        }

        PipelineStatus stage = PipelineStatus.PARSING;
        try {
            String parsedText = parse(document);
            persistence.markParsed(documentId);

            stage = PipelineStatus.CHUNKING;
            List<DocumentChunk> chunks = buildChunks(document, parsedText);
            if (chunks.isEmpty()) {
                throw new BusinessException("文档分块结果为空");
            }
            persistence.replaceChunks(documentId, chunks);

            stage = PipelineStatus.EMBEDDING;
            List<EmbeddedChunk> embeddedChunks = embed(chunks);
            persistence.markEmbeddingCompleted(documentId, chunks);

            stage = PipelineStatus.INDEXING;
            elasticsearchIndexer.bulkIndex(toIndexDocuments(document, embeddedChunks));
            persistence.markCompleted(documentId, chunks);
            return true;
        } catch (RuntimeException ex) {
            persistence.markFailed(documentId, stage, ex);
            throw ex;
        }
    }

    private String parse(Document document) {
        FileRecord file = persistence.loadFile(document.getFileRecordId());
        if (file == null) {
            throw new BusinessException("文件记录不存在: " + document.getFileRecordId());
        }
        try (InputStream inputStream = objectStorageService.getObject(file.getStorageKey())) {
            return documentTextParser.parse(inputStream);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("读取文档对象失败: " + ex.getMessage());
        }
    }

    private List<DocumentChunk> buildChunks(Document document, String parsedText) {
        List<DocumentChunk> chunks = new ArrayList<>();
        List<ChunkSegment> parents = structuredChunker.parentChunks(parsedText);
        int childIndex = 0;
        LocalDateTime createdAt = LocalDateTime.now();
        for (int parentIndex = 0; parentIndex < parents.size(); parentIndex++) {
            ChunkSegment parentSegment = parents.get(parentIndex);
            DocumentChunk parent = chunk(document.getId(), "PARENT", parentIndex, parentSegment, null, createdAt);
            chunks.add(parent);
            for (ChunkSegment childSegment : tokenWindowChunker.childChunks(parentSegment)) {
                chunks.add(chunk(
                        document.getId(),
                        "CHILD",
                        childIndex++,
                        childSegment,
                        parent.getChunkId(),
                        createdAt));
            }
        }
        return List.copyOf(chunks);
    }

    private DocumentChunk chunk(
            Long documentId,
            String chunkType,
            int chunkIndex,
            ChunkSegment segment,
            String parentChunkId,
            LocalDateTime createdAt
    ) {
        String contentHash = sha256(segment.content());
        DocumentChunk chunk = new DocumentChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkId(chunkId(documentId, chunkType, chunkIndex, contentHash));
        chunk.setParentChunkId(parentChunkId);
        chunk.setChunkType(chunkType);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(segment.content());
        chunk.setContentHash(contentHash);
        chunk.setSourceLocation(sourceLocationJson(segment.sourceLocation()));
        chunk.setEmbeddingStatus("PENDING");
        chunk.setCreatedAt(createdAt);
        return chunk;
    }

    private List<EmbeddedChunk> embed(List<DocumentChunk> chunks) {
        return chunks.stream()
                .map(chunk -> new EmbeddedChunk(
                        chunk,
                        embeddingService.embed(chunk.getContent(), EmbeddingPurpose.DOCUMENT)))
                .toList();
    }

    private List<ElasticsearchChunkDocument> toIndexDocuments(
            Document document,
            List<EmbeddedChunk> embeddedChunks
    ) {
        String embeddingModel = aiModelProperties.embedding().model();
        return embeddedChunks.stream().map(embedded -> {
            DocumentChunk chunk = embedded.chunk();
            return new ElasticsearchChunkDocument(
                    document.getUserId() == null ? null : String.valueOf(document.getUserId()),
                    String.valueOf(document.getKnowledgeBaseId()),
                    String.valueOf(document.getId()),
                    chunk.getChunkId(),
                    chunk.getParentChunkId(),
                    chunk.getChunkType(),
                    chunk.getChunkIndex(),
                    chunk.getContent(),
                    chunk.getContentHash(),
                    document.getTitle(),
                    chunk.getSourceLocation(),
                    embedded.embedding(),
                    CHUNKER_VERSION,
                    embeddingModel,
                    chunk.getCreatedAt());
        }).toList();
    }

    String chunkId(Long documentId, String chunkType, int chunkIndex, String contentHash) {
        return sha256(documentId + CHUNKER_VERSION + chunkType + chunkIndex + contentHash);
    }

    private String sourceLocationJson(SourceLocation location) {
        try {
            return objectMapper.writeValueAsString(new StoredSourceLocation(
                    location.startInclusive(),
                    location.endExclusive(),
                    location.headingPath()));
        } catch (JsonProcessingException ex) {
            throw new BusinessException("序列化 chunk 来源坐标失败: " + ex.getMessage());
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }

    private record EmbeddedChunk(DocumentChunk chunk, float[] embedding) {
    }

    private record StoredSourceLocation(int startOffset, int endOffset, List<String> headingPath) {
    }
}
