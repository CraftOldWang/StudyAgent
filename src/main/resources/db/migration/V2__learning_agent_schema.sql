CREATE TABLE IF NOT EXISTS chat_sessions (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    knowledge_base_scope_json JSON NOT NULL,
    web_search_enabled TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_chat_session_user (user_id),
    KEY idx_chat_session_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    tool_name VARCHAR(128),
    tool_call_id VARCHAR(128),
    metadata_json JSON,
    created_at DATETIME NOT NULL,
    KEY idx_chat_message_session (session_id, id),
    KEY idx_chat_message_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_runs (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(32),
    started_at DATETIME NOT NULL,
    finished_at DATETIME,
    error_message TEXT,
    KEY idx_agent_run_session (session_id),
    KEY idx_agent_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_step_records (
    id BIGINT PRIMARY KEY,
    agent_run_id BIGINT NOT NULL,
    stage VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json JSON,
    output_json JSON,
    started_at DATETIME NOT NULL,
    finished_at DATETIME,
    error_message TEXT,
    KEY idx_agent_step_run (agent_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tool_call_records (
    id BIGINT PRIMARY KEY,
    agent_run_id BIGINT,
    session_id BIGINT,
    user_id BIGINT NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    arguments_json JSON NOT NULL,
    result_summary TEXT,
    status VARCHAR(32) NOT NULL,
    permission_checked TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    finished_at DATETIME,
    error_message TEXT,
    KEY idx_tool_call_run (agent_run_id),
    KEY idx_tool_call_session (session_id),
    KEY idx_tool_call_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
