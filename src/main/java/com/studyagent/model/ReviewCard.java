package com.studyagent.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("review_cards")
public class ReviewCard {

    @TableId("id")
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("knowledge_point_id")
    private Long knowledgePointId;

    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    @TableField("front")
    private String front;

    @TableField("back")
    private String back;

    @TableField("source_chunk_id")
    private String sourceChunkId;

    @TableField("exported_to_anki")
    private Boolean exportedToAnki;

    @TableField("anki_note_id")
    private Long ankiNoteId;

    @TableField("anki_export_status")
    private String ankiExportStatus;
    @TableField("anki_export_error")
    private String ankiExportError;
    @TableField("anki_export_attempts")
    private Integer ankiExportAttempts;
    @TableField("anki_exported_at")
    private LocalDateTime ankiExportedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
