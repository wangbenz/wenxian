-- 玩家推演选择和凭证。目录/定价来自服务器只读配置，不提供管理写接口。
CREATE TABLE generation_preference (
    account_id TEXT PRIMARY KEY REFERENCES identity_account(id),
    mode TEXT NOT NULL CHECK(mode IN ('PLATFORM','PERSONAL')),
    tier_code TEXT,
    provider_code TEXT,
    model_code TEXT,
    updated_at INTEGER NOT NULL
);
CREATE TABLE user_model_credential (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES identity_account(id),
    provider_code TEXT NOT NULL,
    ciphertext BLOB, -- 删除或替换时清除旧密文，冻结请求不可换用新密钥
    active INTEGER NOT NULL CHECK(active IN (0,1)),
    created_at INTEGER NOT NULL,
    verified_at INTEGER -- 仅实际成功续写后标记，不因保存成功就宣称服务端验证通过
);
CREATE UNIQUE INDEX one_active_model_credential ON user_model_credential(account_id,provider_code) WHERE active=1;
CREATE TABLE execution_contract (
    save_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    account_id TEXT NOT NULL REFERENCES identity_account(id),
    mode TEXT NOT NULL CHECK(mode IN ('PLATFORM','PERSONAL','FIXTURE')),
    points INTEGER NOT NULL CHECK(points>=0),
    route_json TEXT NOT NULL, -- 仅服务端模型路由和配置散列，不含密钥
    credential_id TEXT REFERENCES user_model_credential(id),
    created_at INTEGER NOT NULL,
    PRIMARY KEY(save_id,request_id),
    FOREIGN KEY(save_id,request_id) REFERENCES action_request(save_id,request_id)
);
CREATE TABLE credit_grant (
    id TEXT PRIMARY KEY,
    account_id TEXT NOT NULL REFERENCES identity_account(id),
    source TEXT NOT NULL,
    amount INTEGER NOT NULL CHECK(amount>=0),
    reserved INTEGER NOT NULL DEFAULT 0 CHECK(reserved>=0),
    spent INTEGER NOT NULL DEFAULT 0 CHECK(spent>=0),
    expires_at INTEGER,
    created_at INTEGER NOT NULL,
    UNIQUE(account_id,source),
    CHECK(reserved+spent<=amount)
);
CREATE INDEX credit_grant_account_expiry ON credit_grant(account_id,expires_at);
CREATE TABLE credit_reservation (
    save_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    account_id TEXT NOT NULL REFERENCES identity_account(id),
    attempt_id TEXT NOT NULL,
    points INTEGER NOT NULL CHECK(points>=0),
    status TEXT NOT NULL CHECK(status IN ('RESERVED','SPENT','RELEASED')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(save_id,request_id),
    FOREIGN KEY(save_id,request_id) REFERENCES execution_contract(save_id,request_id)
);
CREATE TABLE credit_allocation (
    save_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    attempt_id TEXT NOT NULL,
    grant_id TEXT NOT NULL REFERENCES credit_grant(id),
    points INTEGER NOT NULL CHECK(points>0),
    PRIMARY KEY(save_id,request_id,attempt_id,grant_id),
    FOREIGN KEY(save_id,request_id) REFERENCES credit_reservation(save_id,request_id)
);
CREATE TABLE credit_ledger (
    id INTEGER PRIMARY KEY,
    event_key TEXT NOT NULL UNIQUE,
    account_id TEXT NOT NULL REFERENCES identity_account(id),
    grant_id TEXT NOT NULL REFERENCES credit_grant(id),
    save_id TEXT,
    request_id TEXT,
    attempt_id TEXT,
    kind TEXT NOT NULL CHECK(kind IN ('GRANT','RESERVE','SPEND','RELEASE')),
    points INTEGER NOT NULL CHECK(points>=0),
    created_at INTEGER NOT NULL
);
CREATE INDEX credit_ledger_account ON credit_ledger(account_id,id);
