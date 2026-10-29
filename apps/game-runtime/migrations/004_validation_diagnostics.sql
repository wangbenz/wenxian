-- 只保存校验字段路径和错误类型，不保存非法字段原值。
ALTER TABLE model_call ADD COLUMN validation_errors_json TEXT NOT NULL DEFAULT '[]';
