package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("document_chunks")
public class DocumentChunk {

    @TableId("id")
    private Long id;

    @TableField("document_id")
    private Long documentId;

    @TableField("chunk_id")
    private String chunkId;

    @TableField("parent_chunk_id")
    private String parentChunkId;

    @TableField("chunk_type")
    private String chunkType;

    @TableField("chunk_index")
    private Integer chunkIndex;

    @TableField("content")
    private String content;

    @TableField("content_hash")
    private String contentHash;

    @TableField("source_location")
    private String sourceLocation;

    @TableField("embedding_status")
    private String embeddingStatus;

    @TableField("indexed_at")
    private LocalDateTime indexedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
