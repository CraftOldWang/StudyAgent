CREATE TABLE file_records (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    md5 CHAR(32) NOT NULL,
    bucket VARCHAR(128) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128),
    size BIGINT NOT NULL,
    storage_provider VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_file_md5 (md5),
    KEY idx_file_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE upload_sessions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    file_md5 CHAR(32) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128),
    chunk_size INT NOT NULL,
    total_chunks INT NOT NULL,
    uploaded_chunks INT NOT NULL DEFAULT 0,
    file_size BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_upload_user_md5 (user_id, file_md5),
    KEY idx_upload_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_bases (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_kb_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE documents (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    parse_status VARCHAR(32) NOT NULL,
    index_status VARCHAR(32) NOT NULL,
    error_message TEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_doc_kb_id (knowledge_base_id),
    KEY idx_doc_file_id (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE document_chunks (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_chunk_id BIGINT,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    token_count INT NOT NULL,
    metadata_json JSON,
    es_doc_id VARCHAR(128),
    created_at DATETIME NOT NULL,
    KEY idx_chunk_document_id (document_id),
    KEY idx_chunk_kb_id (knowledge_base_id),
    KEY idx_chunk_es_doc_id (es_doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
