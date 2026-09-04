CREATE TABLE document_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    index_version INT NOT NULL,
    chunk_index INT NOT NULL,
    paragraph_number INT NOT NULL,
    content TEXT NOT NULL,
    CONSTRAINT uk_chunk_version UNIQUE (document_id, index_version, chunk_index),
    CONSTRAINT fk_chunk_document FOREIGN KEY (document_id) REFERENCES document(id),
    CONSTRAINT ck_chunk_position CHECK (chunk_index >= 0 AND paragraph_number > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
