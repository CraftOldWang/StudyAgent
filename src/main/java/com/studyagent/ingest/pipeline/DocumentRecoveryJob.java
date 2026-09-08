package com.studyagent.ingest.pipeline;

import com.studyagent.config.DocumentPipelineProperties;
import com.studyagent.ingest.sync.DocumentIndexProducer;
import com.studyagent.mapper.DocumentMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DocumentRecoveryJob {
    private final DocumentMapper mapper;
    private final DocumentIndexProducer producer;
    private final DocumentPipelineProperties properties;

    public void requeueExpired() {
        for (var document : mapper.findRecoveryCandidates(LocalDateTime.now(), properties.recoveryBatchSize())) {
            producer.send(document.getId(), document.getUserId());
        }
    }
}
