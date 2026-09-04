ALTER TABLE document_task
    ADD COLUMN requested_by BIGINT NULL,
    ADD COLUMN reindex_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_task_requester FOREIGN KEY (requested_by) REFERENCES app_user(id),
    ADD CONSTRAINT uk_task_reindex_request UNIQUE (document_id, requested_by, reindex_key);
