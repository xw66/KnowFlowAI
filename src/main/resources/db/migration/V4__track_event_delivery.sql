ALTER TABLE outbox_event
    ADD COLUMN lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER lease_until,
    ADD INDEX idx_outbox_expired_lease (status, lease_until);

ALTER TABLE document_task
    ADD COLUMN received_at DATETIME(6) NULL AFTER stage;
