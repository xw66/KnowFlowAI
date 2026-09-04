CREATE TABLE conversation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_conversation_user (user_id, id),
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_conversation_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content TEXT NOT NULL,
    model VARCHAR(128),
    input_tokens INT,
    output_tokens INT,
    total_tokens INT,
    error_code VARCHAR(64),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6),
    INDEX idx_message_conversation (conversation_id, id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT ck_message_role CHECK (role IN ('USER','ASSISTANT')),
    CONSTRAINT ck_message_status CHECK (status IN ('RUNNING','COMPLETED','FAILED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE message_citation (
    message_id BIGINT NOT NULL,
    citation_id VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    document_name VARCHAR(255) NOT NULL,
    page_number INT,
    paragraph_number INT NOT NULL,
    source_content TEXT NOT NULL,
    quoted_text TEXT,
    cited BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (message_id, citation_id),
    CONSTRAINT fk_citation_message FOREIGN KEY (message_id) REFERENCES chat_message(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
