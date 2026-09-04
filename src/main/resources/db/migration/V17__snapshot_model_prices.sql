CREATE TABLE model_price (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version VARCHAR(64) NOT NULL,
    call_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    endpoint VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    input_per_million DECIMAL(12,6),
    output_per_million DECIMAL(12,6),
    total_per_million DECIMAL(12,6),
    max_input_tokens INT,
    verified_at DATE NOT NULL,
    source_url VARCHAR(512) NOT NULL,
    UNIQUE KEY uk_model_price (version,call_type,endpoint,model),
    CONSTRAINT ck_model_price_currency CHECK (currency='CNY'),
    CONSTRAINT ck_model_price_rates CHECK (
        (input_per_million IS NOT NULL AND input_per_million>=0 AND output_per_million IS NOT NULL AND output_per_million>=0 AND total_per_million IS NULL)
        OR (input_per_million IS NULL AND output_per_million IS NULL AND total_per_million IS NOT NULL AND total_per_million>=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO model_price(version,call_type,endpoint,model,input_per_million,output_per_million,total_per_million,max_input_tokens,verified_at,source_url) VALUES
('bailian-beijing-2026-09-04','CHAT','https://dashscope.aliyuncs.com/compatible-mode/v1','qwen3.8-flash',0.8,2.7,NULL,1000000,'2026-09-04','https://help.aliyun.com/zh/model-studio/model-pricing'),
('bailian-beijing-2026-09-04','EMBEDDING','https://dashscope.aliyuncs.com/compatible-mode/v1','text-embedding-v4',0.5,0,NULL,NULL,'2026-09-04','https://help.aliyun.com/zh/model-studio/model-pricing'),
('bailian-beijing-2026-09-04','RERANK','https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank','gte-rerank-v2',NULL,NULL,0.8,NULL,'2026-09-04','https://help.aliyun.com/zh/model-studio/model-pricing');

ALTER TABLE model_call
    ADD COLUMN price_version VARCHAR(64),
    ADD COLUMN price_currency CHAR(3),
    ADD COLUMN price_input_per_million DECIMAL(12,6),
    ADD COLUMN price_output_per_million DECIMAL(12,6),
    ADD COLUMN price_total_per_million DECIMAL(12,6),
    ADD COLUMN price_max_input_tokens INT,
    ADD COLUMN price_verified_at DATE,
    ADD COLUMN price_source_url VARCHAR(512),
    ADD COLUMN estimated_cost DECIMAL(20,12),
    ADD CONSTRAINT ck_model_call_cost CHECK (estimated_cost IS NULL OR estimated_cost>=0);
