ALTER TABLE document_task
    ADD COLUMN vector_cursor INT NOT NULL DEFAULT -1,
    ADD COLUMN vector_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN vector_collection VARCHAR(128) NULL;
ALTER TABLE document ADD COLUMN vector_collection VARCHAR(128) NULL;
