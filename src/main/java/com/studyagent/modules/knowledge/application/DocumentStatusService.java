package com.studyagent.modules.knowledge.application;

import com.studyagent.modules.knowledge.domain.Document;
import com.studyagent.modules.knowledge.infrastructure.DocumentMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentStatusService {

    private final DocumentMapper documentMapper;

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
