package com.studyagent.modules.knowledge.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档切块实体，是 RAG 检索、引用展示和 ES 索引同步的最小业务单元。
 */
@Getter
@Setter
@TableName("document_chunks")
public class DocumentChunk {
    private Long id;
    private Long documentId;
    private Long knowledgeBaseId;
    private Long userId;
    private Long parentChunkId;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private String metadataJson;
    private String esDocId;
    private LocalDateTime createdAt;
}
