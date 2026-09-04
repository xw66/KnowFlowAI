ALTER TABLE model_call
    ADD COLUMN task_id BIGINT NULL,
    ADD CONSTRAINT fk_model_call_task FOREIGN KEY (task_id) REFERENCES document_task(id) ON DELETE SET NULL;
