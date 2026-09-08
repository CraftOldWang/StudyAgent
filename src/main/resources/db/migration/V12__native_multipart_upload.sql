-- A conflict must fail this migration; historical records must never be silently merged.
ALTER TABLE file_records ADD CONSTRAINT uk_file_scope_hash UNIQUE (user_id, knowledge_base_id, file_hash);
ALTER TABLE documents ADD CONSTRAINT uk_document_scope_file UNIQUE (user_id, knowledge_base_id, file_record_id);

ALTER TABLE upload_sessions
    ADD COLUMN storage_upload_id VARCHAR(512),
    ADD COLUMN storage_key VARCHAR(512),
    ADD COLUMN error_message VARCHAR(2048);

CREATE TABLE upload_parts (
    id BIGINT PRIMARY KEY,
    upload_session_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    etag VARCHAR(255) NOT NULL,
    byte_size BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_upload_part (upload_session_id, chunk_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
