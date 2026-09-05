package com.studyagent.ingest.pipeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import com.studyagent.model.Document;
import com.studyagent.model.DocumentChunk;
import com.studyagent.model.FileRecord;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentPipelinePersistence {

    private final DocumentMapper documentMapper;
    private final FileRecordMapper fileRecordMapper;
    private final DocumentChunkMapper documentChunkMapper;

    @Transactional
    public Document claim(Long documentId, boolean allowFailedRetry) {
        var update = Wrappers.<Document>update()
                .eq("id", documentId)
                .set("pipeline_status", PipelineStatus.PARSING.name())
                .set("error_message", null)
                .set("updated_at", LocalDateTime.now());
        if (allowFailedRetry) {
            update.in("pipeline_status", PipelineStatus.STORED.name(), PipelineStatus.FAILED.name());
        } else {
            update.eq("pipeline_status", PipelineStatus.STORED.name());
        }
        if (documentMapper.update(null, update) != 1) {
            return null;
        }
        return documentMapper.selectById(documentId);
    }

    public FileRecord loadFile(Long fileRecordId) {
        return fileRecordMapper.selectById(fileRecordId);
    }

    @Transactional
    public void markParsed(Long documentId) {
        advance(documentId, PipelineStatus.PARSING, PipelineStatus.PARSED, update ->
                update.set("parser_version", DocumentPipeline.PARSER_VERSION));
    }

    @Transactional
    public void replaceChunks(Long documentId, List<DocumentChunk> chunks) {
        documentChunkMapper.delete(Wrappers.<DocumentChunk>query()
                .eq("document_id", documentId));
        for (DocumentChunk chunk : chunks) {
            documentChunkMapper.insert(chunk);
        }
        advance(documentId, PipelineStatus.PARSED, PipelineStatus.CHUNKED, update ->
                update.set("chunker_version", DocumentPipeline.CHUNKER_VERSION));
    }

    @Transactional
    public void markEmbeddingCompleted(Long documentId, List<DocumentChunk> chunks) {
        for (DocumentChunk chunk : chunks) {
            chunk.setEmbeddingStatus("COMPLETED");
            documentChunkMapper.updateById(chunk);
        }
        advance(documentId, PipelineStatus.CHUNKED, PipelineStatus.EMBEDDED);
    }

    @Transactional
    public void markCompleted(Long documentId, List<DocumentChunk> chunks) {
        LocalDateTime indexedAt = LocalDateTime.now();
        for (DocumentChunk chunk : chunks) {
            chunk.setIndexedAt(indexedAt);
            documentChunkMapper.updateById(chunk);
        }
        advance(documentId, PipelineStatus.EMBEDDED, PipelineStatus.INDEXED, update ->
                update.set("error_message", null));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long documentId, PipelineStatus stage, Throwable failure) {
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        documentMapper.update(null, Wrappers.<Document>update()
                .eq("id", documentId)
                .set("pipeline_status", PipelineStatus.FAILED.name())
                .set("error_message", stage.name() + ": " + detail)
                .set("updated_at", LocalDateTime.now()));
    }

    private void advance(
            Long documentId,
            PipelineStatus expected,
            PipelineStatus next
    ) {
        advance(documentId, expected, next, update -> {
        });
    }

    private void advance(
            Long documentId,
            PipelineStatus expected,
            PipelineStatus next,
            java.util.function.Consumer<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Document>>
                    customization
    ) {
        var update = Wrappers.<Document>update()
                .eq("id", documentId)
                .eq("pipeline_status", expected.name())
                .set("pipeline_status", next.name())
                .set("updated_at", LocalDateTime.now());
        customization.accept(update);
        if (documentMapper.update(null, update) != 1) {
            throw new BusinessException("文档 pipeline 状态推进失败: documentId=" + documentId
                    + ", expected=" + expected + ", next=" + next);
        }
    }
}
