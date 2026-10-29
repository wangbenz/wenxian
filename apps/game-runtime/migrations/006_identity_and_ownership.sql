-- 独立本地账号。角色与登录名组成唯一身份，不从旧试玩会话推断账号。
CREATE TABLE identity_account (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK(kind IN ('PLAYER','ADMIN')),
    login TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('PLAYER','OWNER','OPERATOR')),
    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
    created_at INTEGER NOT NULL,
    password_changed_at INTEGER NOT NULL,
    UNIQUE(kind,login),
    CHECK((kind='PLAYER' AND role='PLAYER') OR (kind='ADMIN' AND role IN ('OWNER','OPERATOR')))
);
CREATE TABLE identity_session (
    token_hash TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES identity_account(id),
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    revoked_at INTEGER
);
CREATE INDEX identity_session_account ON identity_session(account_id);
-- 限流标识使用服务端随机 pepper 的 HMAC，不保存原 IP 或错误密码。
CREATE TABLE identity_attempt (
    id INTEGER PRIMARY KEY,
    scope TEXT NOT NULL,
    subject_hash TEXT NOT NULL,
    client_hash TEXT NOT NULL,
    occurred_at INTEGER NOT NULL,
    success INTEGER NOT NULL CHECK(success IN (0,1))
);
CREATE INDEX identity_attempt_subject ON identity_attempt(scope,subject_hash,occurred_at);
CREATE INDEX identity_attempt_client ON identity_attempt(scope,client_hash,occurred_at);
CREATE TABLE runtime_private_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
-- 只记录权限操作与非敏感身份编号，不存密码、token 或邮箱。
CREATE TABLE runtime_admin_audit (
    id INTEGER PRIMARY KEY,
    actor_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    target_id TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
-- 命卷归属与列表索引。旧试玩卷不回填，历史和已确认投影不改写。
CREATE TABLE account_save (
    save_id TEXT PRIMARY KEY REFERENCES runtime_save(id),
    account_id TEXT NOT NULL REFERENCES identity_account(id),
    creation_request_id TEXT NOT NULL,
    creation_fingerprint TEXT NOT NULL,
    name TEXT NOT NULL,
    realm TEXT NOT NULL,
    location TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(account_id,creation_request_id)
);
CREATE INDEX account_save_owner ON account_save(account_id,updated_at);
