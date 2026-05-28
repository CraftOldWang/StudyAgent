package com.studyagent.modules.knowledge.application;

import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档状态更新服务，专门用独立事务记录失败状态。
 *
 * <p>文档处理链路失败后外层事务可能回滚，因此失败状态必须在新事务中单独落库。</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentStatusService {

    private final DocumentMapper documentMapper;

    /**
     * 将文档解析和索引状态都标记为失败。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long documentId, String errorMessage) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            return;
        }
        document.setParseStatus("FAILED");
        document.setIndexStatus("FAILED");
        document.setErrorMessage(errorMessage);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
    }

    /**
     * 仅标记索引阶段失败，保留解析成功状态，便于后续重试索引。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexFailed(Long documentId, String errorMessage) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) {
            return;
        }
        document.setParseStatus("PARSED");
        document.setIndexStatus("FAILED");
        document.setErrorMessage(errorMessage);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
    }
}
