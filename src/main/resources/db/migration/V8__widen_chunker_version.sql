-- Fingerprints include tokenizer, strategy, and all child/parent window parameters.
ALTER TABLE documents MODIFY COLUMN chunker_version VARCHAR(128);
