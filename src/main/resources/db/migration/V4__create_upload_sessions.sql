CREATE TABLE upload_sessions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(64),
    chunk_size INT NOT NULL,
    total_chunks INT NOT NULL,
    uploaded_chunks INT NOT NULL,
    file_size BIGINT NOT NULL,
    completed_file_id BIGINT,
    completed_document_id BIGINT,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_upload_resume (user_id, knowledge_base_id, file_hash, status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
