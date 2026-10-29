-- 创角幂等资料指纹，只绑定本地随机会话凭据，不改旧投影和历史
ALTER TABLE runtime_session ADD COLUMN creation_fingerprint TEXT;
