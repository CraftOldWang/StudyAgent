ALTER TABLE document_chunks
    ADD COLUMN chunk_type VARCHAR(16) NOT NULL DEFAULT 'CHILD' AFTER parent_chunk_id;

CREATE INDEX idx_chunk_parent_type ON document_chunks (parent_chunk_id, chunk_type);
CREATE INDEX idx_chunk_document_type ON document_chunks (document_id, chunk_type, chunk_index);
