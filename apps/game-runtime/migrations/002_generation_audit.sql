-- 模型与提示词标识仅保存在服务端审计，不返回玩家页面。
ALTER TABLE committed_turn ADD COLUMN generation_audit_json TEXT NOT NULL DEFAULT '{}';
