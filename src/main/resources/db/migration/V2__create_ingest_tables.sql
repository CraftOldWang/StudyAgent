CREATE TABLE file_records (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    file_hash VARCHAR(64) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_user_kb (user_id, knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE documents (
    id BIGINT PRIMARY KEY,
    file_record_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    title VARCHAR(512),
    content_type VARCHAR(64),
    pipeline_status VARCHAR(32) NOT NULL,
    error_message TEXT,
    parser_version VARCHAR(32),
    chunker_version VARCHAR(32),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_user_kb (user_id, knowledge_base_id),
    INDEX idx_status (pipeline_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE document_chunks (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_id VARCHAR(64) NOT NULL UNIQUE,
    parent_chunk_id VARCHAR(64),
    chunk_type VARCHAR(16) NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    source_location JSON,
    embedding_status VARCHAR(32),
    indexed_at DATETIME,
    created_at DATETIME NOT NULL,
    INDEX idx_document (document_id),
    INDEX idx_parent (parent_chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
