package com.studyagent.ingest.upload;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.studyagent.ingest.sync.DocumentIndexProducer;
import com.studyagent.ingest.web.UploadResultResponse;
import com.studyagent.mapper.DocumentMapper;
import com.studyagent.mapper.FileRecordMapper;
import com.studyagent.mapper.UploadSessionMapper;
import com.studyagent.model.Document;
import com.studyagent.model.FileRecord;
import com.studyagent.model.UploadSession;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UploadPublicationService {
    private final FileRecordMapper files;
    private final DocumentMapper documents;
    private final UploadSessionMapper sessions;
    private final DocumentIndexProducer producer;

    public FileRecord find(Long userId, Long kbId, String hash) {
        return files.selectOne(fileQuery(userId, kbId, hash));
    }

    // The caller keeps its distributed lock until this short transaction commits.
    @Transactional
    public UploadResultResponse publish(Long userId, Long kbId, String hash, String key,
                                        String filename, long size, String contentType, Long uploadSessionId) {
        FileRecord file = find(userId, kbId, hash);
        boolean created = false;
        if (file == null) {
            file = new FileRecord();
            file.setUserId(userId);
            file.setKnowledgeBaseId(kbId);
            file.setFileHash(hash);
            file.setStorageKey(key);
            file.setFilename(UploadFileSupport.filename(filename));
            file.setFileSize(size);
            file.setStatus("STORED");
            file.setCreatedAt(LocalDateTime.now());
            try {
                files.insert(file);
                created = true;
            } catch (DuplicateKeyException ex) {
                // Current read sees the winning commit even under MySQL REPEATABLE READ.
                file = files.selectOne(fileQuery(userId, kbId, hash).last("FOR UPDATE"));
                if (file == null) throw ex;
            }
        }
        Document document = documents.selectOne(documentQuery(userId, kbId, file.getId()));
        if (document == null) {
            document = new Document();
            document.setUserId(userId);
            document.setKnowledgeBaseId(kbId);
            document.setFileRecordId(file.getId());
            document.setTitle(UploadFileSupport.filename(filename));
            document.setContentType(UploadFileSupport.contentType(contentType));
            document.setPipelineStatus("STORED");
            document.setCreatedAt(LocalDateTime.now());
            document.setUpdatedAt(LocalDateTime.now());
            boolean documentCreated = false;
            try {
                documents.insert(document);
                documentCreated = true;
            } catch (DuplicateKeyException ex) {
                document = documents.selectOne(documentQuery(userId, kbId, file.getId()).last("FOR UPDATE"));
                if (document == null) throw ex;
            }
            if (documentCreated) producer.send(document.getId(), userId);
        }
        if (uploadSessionId != null) {
            UploadSession completed = new UploadSession();
            completed.setId(uploadSessionId);
            completed.setStatus("COMPLETED");
            completed.setCompletedFileId(file.getId());
            completed.setCompletedDocumentId(document.getId());
            completed.setUpdatedAt(LocalDateTime.now());
            sessions.updateById(completed);
            sessions.setPhase(uploadSessionId, "COMPLETED", LocalDateTime.now());
        }
        return new UploadResultResponse(file.getId(), document.getId(), created ? "UPLOADED" : "DUPLICATED");
    }

    private LambdaQueryWrapper<FileRecord> fileQuery(Long userId, Long kbId, String hash) {
        return new LambdaQueryWrapper<FileRecord>().eq(FileRecord::getUserId, userId)
                .eq(FileRecord::getKnowledgeBaseId, kbId).eq(FileRecord::getFileHash, hash);
    }

    private LambdaQueryWrapper<Document> documentQuery(Long userId, Long kbId, Long fileId) {
        return new LambdaQueryWrapper<Document>().eq(Document::getUserId, userId)
                .eq(Document::getKnowledgeBaseId, kbId).eq(Document::getFileRecordId, fileId);
    }
}
