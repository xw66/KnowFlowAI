CREATE TABLE vector_cleanup (
    document_id BIGINT NOT NULL,
    collection_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_token CHAR(36) CHARACTER SET ascii,
    attempts INT NOT NULL DEFAULT 0,
    last_cleaned_at DATETIME(6),
    error_code VARCHAR(64),
    PRIMARY KEY (document_id, collection_name),
    KEY idx_cleanup_available (available_at),
    CONSTRAINT fk_cleanup_document FOREIGN KEY (document_id) REFERENCES document(id)
);
