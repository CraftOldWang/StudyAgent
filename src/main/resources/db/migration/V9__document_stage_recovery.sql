ALTER TABLE documents
    ADD COLUMN parsed_text_key VARCHAR(512),
    ADD COLUMN parsed_text_hash VARCHAR(64),
    ADD COLUMN last_successful_stage VARCHAR(32),
    ADD COLUMN processing_token VARCHAR(36),
    ADD COLUMN lease_until DATETIME(6),
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN index_target VARCHAR(255),
    ADD INDEX idx_pipeline_lease (pipeline_status, lease_until);

CREATE TABLE embedding_artifacts (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    dimensions INT NOT NULL,
    vector_json MEDIUMTEXT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_embedding_artifact (user_id, content_hash, model, dimensions)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
