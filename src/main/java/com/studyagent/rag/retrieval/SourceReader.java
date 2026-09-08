package com.studyagent.rag.retrieval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.mapper.DocumentChunkMapper;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.model.DocumentChunk;
import com.studyagent.rag.web.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SourceReader {
    private final KnowledgeBaseService knowledgeBases;
    private final DocumentChunkMapper chunks;
    private final DocumentMapper documents;

    public Source read(Long userId, Long knowledgeBaseId, String chunkId) {
        knowledgeBases.requireOwned(userId, knowledgeBaseId);
        var chunk = chunks.selectOne(Wrappers.<DocumentChunk>query().eq("chunk_id", chunkId));
        if (chunk == null) { throw new BusinessException(404, "引用资料不存在或已重新处理"); }
        var document = documents.selectById(chunk.getDocumentId());
        if (document == null || !userId.equals(document.getUserId()) || !knowledgeBaseId.equals(document.getKnowledgeBaseId())) {
            throw new BusinessException(404, "引用资料不属于当前资料库");
        }
        return new Source(chunk.getChunkId(), document.getId(), document.getTitle(), chunk.getSourceLocation(), chunk.getContent());
    }

    public record Source(String chunkId, Long documentId, String documentTitle, String sourceLocation, String content) { }
}
