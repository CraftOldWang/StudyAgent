CREATE TABLE learning_plan_runs (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    learning_goal TEXT NOT NULL,
    input_json JSON NOT NULL,
    status VARCHAR(24) NOT NULL,
    error_message TEXT NULL,
    processing_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    session_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_plan_run_user (user_id, knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_plan_stages (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    stage_key VARCHAR(100) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    input_json JSON NOT NULL,
    output_json JSON NULL,
    raw_output LONGTEXT NULL,
    usage_json JSON NULL,
    status VARCHAR(24) NOT NULL,
    error_message TEXT NULL,
    trace_id VARCHAR(36) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    started_at DATETIME NOT NULL,
    completed_at DATETIME NULL,
    elapsed_millis BIGINT NULL,
    UNIQUE KEY uk_plan_stage_attempt (run_id, stage_key, attempt_count),
    INDEX idx_plan_stage_user (user_id, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE knowledge_points
    ADD COLUMN chapter_id BIGINT NULL,
    ADD COLUMN chapter_title VARCHAR(512) NULL,
    ADD COLUMN priority VARCHAR(16) NULL,
    ADD COLUMN sources_json JSON NULL;
