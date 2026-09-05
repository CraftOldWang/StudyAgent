ALTER TABLE learning_sessions
    ADD COLUMN learning_goal TEXT NULL AFTER knowledge_base_id,
    ADD COLUMN active_knowledge_point_id BIGINT NULL AFTER agentscope_session_id,
    ADD COLUMN error_message TEXT NULL AFTER status;

ALTER TABLE knowledge_points
    ADD COLUMN sequence_no INT NULL AFTER user_id,
    ADD COLUMN subtopics_json JSON NULL AFTER topic,
    ADD COLUMN estimated_minutes INT NULL AFTER subtopics_json,
    ADD COLUMN explanation TEXT NULL AFTER status,
    ADD COLUMN error_message TEXT NULL AFTER explanation;

UPDATE learning_sessions
SET learning_goal = ''
WHERE learning_goal IS NULL;

UPDATE knowledge_points AS point
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY created_at, id) AS generated_sequence_no
    FROM knowledge_points
) AS ranked ON ranked.id = point.id
SET point.sequence_no = ranked.generated_sequence_no,
    point.subtopics_json = JSON_ARRAY(),
    point.estimated_minutes = 1;

ALTER TABLE learning_sessions
    MODIFY COLUMN learning_goal TEXT NOT NULL;

ALTER TABLE knowledge_points
    MODIFY COLUMN sequence_no INT NOT NULL,
    MODIFY COLUMN subtopics_json JSON NOT NULL,
    MODIFY COLUMN estimated_minutes INT NOT NULL,
    ADD UNIQUE KEY uk_knowledge_points_session_sequence (session_id, sequence_no);

CREATE TABLE quizzes (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    knowledge_point_id BIGINT NOT NULL,
    questions_json JSON NOT NULL,
    answers_json JSON NULL,
    score INT NULL,
    feedback_json JSON NULL,
    created_at DATETIME NOT NULL,
    answered_at DATETIME NULL,
    UNIQUE KEY uk_quizzes_knowledge_point (knowledge_point_id),
    INDEX idx_quizzes_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_trace_events (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    trace_id VARCHAR(36) NOT NULL,
    session_id BIGINT NULL,
    sequence_no INT NOT NULL,
    stage VARCHAR(32) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_trace_sequence (trace_id, sequence_no),
    INDEX idx_trace_user (user_id, trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
