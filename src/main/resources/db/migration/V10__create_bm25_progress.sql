CREATE TABLE bm25_index_progress (
    document_id BIGINT NOT NULL PRIMARY KEY,
    index_version INT NOT NULL,
    instance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    committed_at DATETIME(6),
    error_code VARCHAR(64),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_bm25_document FOREIGN KEY (document_id) REFERENCES document(id),
    CONSTRAINT ck_bm25_status CHECK (status IN ('READY','CLEANED','RETRY_WAIT')),
    CONSTRAINT ck_bm25_version CHECK (index_version >= 0),
    INDEX idx_bm25_available (available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
