CREATE TABLE IF NOT EXISTS learning_todos (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL,
    order_index INT NOT NULL,
    round_summary TEXT,
    started_at DATETIME,
    completed_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_learning_todo_session_order (session_id, order_index),
    KEY idx_learning_todo_session_status (session_id, status, order_index),
    KEY idx_learning_todo_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
