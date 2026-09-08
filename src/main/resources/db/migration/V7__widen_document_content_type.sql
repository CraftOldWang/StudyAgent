-- Standard PPTX MIME names exceed the original 64-character PDF-only limit.
ALTER TABLE documents MODIFY COLUMN content_type VARCHAR(255);
ALTER TABLE upload_sessions MODIFY COLUMN content_type VARCHAR(255);
