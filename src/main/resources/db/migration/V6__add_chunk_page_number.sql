ALTER TABLE document_chunk
    ADD COLUMN page_number INT NULL AFTER paragraph_number,
    ADD CONSTRAINT ck_chunk_page CHECK (page_number IS NULL OR page_number > 0);
