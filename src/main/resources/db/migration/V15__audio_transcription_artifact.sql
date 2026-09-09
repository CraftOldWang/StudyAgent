ALTER TABLE documents
    ADD COLUMN asr_result_key VARCHAR(512) NULL,
    ADD COLUMN asr_metadata_json JSON NULL;
