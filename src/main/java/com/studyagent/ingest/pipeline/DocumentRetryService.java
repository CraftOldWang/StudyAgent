package com.studyagent.ingest.pipeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.ingest.sync.DocumentIndexProducer;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.model.Document;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentRetryService {
    private final DocumentMapper documentMapper;
    private final DocumentIndexProducer producer;

    @Transactional
    public void retry(Long userId, Long documentId) {
        Document document = documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
                .eq(Document::getId, documentId).eq(Document::getUserId, userId));
        if (document == null) { throw new BusinessException("文档不存在或无访问权限"); }
        if (!PipelineStatus.FAILED.name().equals(document.getPipelineStatus())) {
            throw new BusinessException("仅失败的文档可以请求重试");
        }
        producer.send(documentId, userId);
    }
}
