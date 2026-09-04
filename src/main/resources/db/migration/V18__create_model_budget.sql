CREATE TABLE model_budget (
  id BIGINT PRIMARY KEY,
  limit_cny DECIMAL(20,12) NOT NULL,
  spent_cny DECIMAL(20,12) NOT NULL DEFAULT 0,
  held_cny DECIMAL(20,12) NOT NULL DEFAULT 0,
  halted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_model_budget_amounts CHECK (limit_cny>=0 AND spent_cny>=0 AND held_cny>=0)
);
INSERT INTO model_budget(id,limit_cny) VALUES (1,20.000000000000);
ALTER TABLE model_call ADD COLUMN budget_reserved DECIMAL(20,12) NULL;
ALTER TABLE model_call ADD COLUMN budget_settled BOOLEAN NOT NULL DEFAULT FALSE;
