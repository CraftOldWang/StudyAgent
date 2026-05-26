ALTER TABLE file_records
    ADD COLUMN sha256 CHAR(64) NULL AFTER md5;

ALTER TABLE file_records
    ADD UNIQUE KEY uk_file_sha256 (sha256);

ALTER TABLE upload_sessions
    ADD COLUMN knowledge_base_id BIGINT NULL AFTER user_id,
    ADD COLUMN completed_file_id BIGINT NULL AFTER file_size,
    ADD COLUMN completed_document_id BIGINT NULL AFTER completed_file_id;

CREATE INDEX idx_upload_user_status_md5 ON upload_sessions (user_id, status, file_md5);
