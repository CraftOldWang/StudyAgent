CREATE TABLE IF NOT EXISTS quiz_questions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT,
    document_id BIGINT,
    session_id BIGINT,
    agent_run_id BIGINT,
    question_type VARCHAR(32) NOT NULL,
    question_text TEXT NOT NULL,
    correct_answer TEXT NOT NULL,
    explanation TEXT,
    options_json JSON,
    source_chunk_ids_json JSON,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_quiz_question_user_kb (user_id, knowledge_base_id, created_at),
    KEY idx_quiz_question_session (session_id, created_at),
    KEY idx_quiz_question_run (agent_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quiz_answers (
    id BIGINT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id BIGINT,
    user_answer TEXT NOT NULL,
    evaluation TEXT,
    correct TINYINT(1),
    score INT,
    answered_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    KEY idx_quiz_answer_question (question_id, answered_at),
    KEY idx_quiz_answer_user (user_id, answered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
