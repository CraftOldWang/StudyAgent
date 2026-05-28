package com.studyagent.modules.knowledge.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 知识库实体，表示用户可选择的检索和学习资料范围。
 */
@Getter
@Setter
@TableName("knowledge_bases")
public class KnowledgeBase {
    private Long id;
    private Long userId;
    private String name;
    private String description;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
