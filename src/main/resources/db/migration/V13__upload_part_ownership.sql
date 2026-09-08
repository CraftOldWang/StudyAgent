ALTER TABLE upload_parts ADD COLUMN user_id BIGINT;
UPDATE upload_parts p JOIN upload_sessions s ON p.upload_session_id = s.id SET p.user_id = s.user_id;
ALTER TABLE upload_parts MODIFY COLUMN user_id BIGINT NOT NULL;
