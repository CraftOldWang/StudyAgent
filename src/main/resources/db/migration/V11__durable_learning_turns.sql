ALTER TABLE learning_sessions ADD COLUMN active_turn_id BIGINT NULL;
ALTER TABLE agent_trace_events
    ADD COLUMN payload_json JSON NULL,
    ADD COLUMN elapsed_millis BIGINT NULL,
    ADD COLUMN tool_call_id VARCHAR(128) NULL;

CREATE TABLE learning_turns (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    knowledge_point_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    user_message TEXT NOT NULL,
    assistant_message LONGTEXT NULL,
    artifact_json JSON NULL,
    context_delta_json LONGTEXT NULL,
    prepared_context_json LONGTEXT NULL,
    status VARCHAR(24) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    error_message TEXT NULL,
    processing_token VARCHAR(36) NULL,
    lease_until DATETIME(6) NULL,
    trace_id VARCHAR(36) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_learning_turn_request (session_id, request_id),
    INDEX idx_learning_turn_user (user_id, session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_contexts (
    session_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    agent_state_json LONGTEXT NOT NULL,
    compression_strategy VARCHAR(24) NOT NULL,
    updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_compactions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    turn_id BIGINT NOT NULL,
    knowledge_point_id BIGINT NULL,
    kind VARCHAR(24) NOT NULL,
    input_hash CHAR(64) NOT NULL,
    summary_text LONGTEXT NOT NULL,
    trace_id VARCHAR(36) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_learning_compaction (turn_id, kind, input_hash),
    INDEX idx_learning_compaction_user (user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
