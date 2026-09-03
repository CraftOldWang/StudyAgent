CREATE TABLE learning_sessions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    agentscope_session_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_user (user_id),
    INDEX idx_as_session (agentscope_session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_plan (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    plan_json JSON NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_points (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    topic VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME,
    completed_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_session_status (session_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE review_cards (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_point_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    front TEXT NOT NULL,
    back TEXT NOT NULL,
    source_chunk_id VARCHAR(64),
    exported_to_anki BOOLEAN DEFAULT FALSE,
    anki_note_id BIGINT,
    created_at DATETIME NOT NULL,
    INDEX idx_user (user_id),
    INDEX idx_kp (knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
