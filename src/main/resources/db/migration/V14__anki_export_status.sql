ALTER TABLE review_cards
    ADD COLUMN anki_export_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN anki_export_error VARCHAR(2048) NULL,
    ADD COLUMN anki_export_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN anki_exported_at DATETIME NULL;

UPDATE review_cards SET anki_export_status = 'SUCCEEDED' WHERE exported_to_anki = 1;
