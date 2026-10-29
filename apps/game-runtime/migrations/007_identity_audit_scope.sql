-- 用户已将管理台排除在本次迁移范围。仅保留玩家身份安全审计职责。
-- 006 已应用于本地副本，不改写既有迁移或删除其中的验收记录。
ALTER TABLE runtime_admin_audit RENAME TO identity_security_audit;
