ALTER TABLE chat_message
    ADD COLUMN original_question VARCHAR(2000),
    ADD COLUMN retrieval_query VARCHAR(2000),
    ADD COLUMN rewrite_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN rewrite_model VARCHAR(128),
    ADD COLUMN rewrite_input_tokens INT,
    ADD COLUMN rewrite_output_tokens INT,
    ADD COLUMN rewrite_total_tokens INT,
    ADD COLUMN rewrite_source_message_id BIGINT,
    ADD CONSTRAINT fk_rewrite_source FOREIGN KEY (rewrite_source_message_id) REFERENCES chat_message(id),
    ADD CONSTRAINT ck_rewrite_status CHECK (rewrite_status IN ('NOT_REQUESTED','NO_CONTEXT','UNCHANGED','APPLIED','FALLBACK'));
