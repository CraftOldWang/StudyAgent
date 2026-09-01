package com.studyagent.modules.knowledge.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 文档切块实体，是 RAG 检索、引用展示和 ES 索引同步的最小业务单元。
 *
 * <p>父子检索中，PARENT chunk 保存较完整的上下文，CHILD chunk 保存更小的召回单元。
 * ES 只检索 CHILD，再通过 parentChunkId 回到 PARENT，避免“命中点很准但上下文不够”的问题。</p>
 */
@Getter
@Setter
@TableName("document_chunks")
public class DocumentChunk {
    public static final String TYPE_PARENT = "PARENT";
    public static final String TYPE_CHILD = "CHILD";

    private Long id;
    private Long documentId;
    private Long knowledgeBaseId;
    private Long userId;
    private Long parentChunkId;
    private String chunkType;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private String metadataJson;
    private String esDocId;
    private LocalDateTime createdAt;
}
