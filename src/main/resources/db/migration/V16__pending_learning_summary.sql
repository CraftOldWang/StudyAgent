ALTER TABLE learning_contexts
    ADD COLUMN pending_point_id BIGINT NULL,
    ADD COLUMN pending_summary_input LONGTEXT NULL,
    ADD COLUMN pending_summary_text LONGTEXT NULL,
    ADD COLUMN pending_summary_status VARCHAR(24) NULL,
    ADD COLUMN pending_summary_error TEXT NULL;
