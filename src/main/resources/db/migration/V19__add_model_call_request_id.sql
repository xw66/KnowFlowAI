ALTER TABLE model_call ADD COLUMN request_id VARCHAR(100) NULL;
CREATE INDEX ix_model_call_request_id ON model_call(request_id);
