-- 调用尝试仍在发送前预占；结果与用量只做服务端审计，不等于玩家回合消费。
ALTER TABLE model_call ADD COLUMN outcome TEXT NOT NULL DEFAULT 'RESERVED';
ALTER TABLE model_call ADD COLUMN duration_ms INTEGER;
ALTER TABLE model_call ADD COLUMN usage_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE model_call ADD COLUMN error_type TEXT;
ALTER TABLE model_call ADD COLUMN provider_status INTEGER;
ALTER TABLE model_call ADD COLUMN completed_at TEXT;
