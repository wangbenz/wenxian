-- 独立试玩 SQLite；不操作原 MySQL 或资料库表。
CREATE TABLE runtime_session (
    id TEXT PRIMARY KEY, -- 本地会话身份
    token_hash TEXT NOT NULL UNIQUE, -- 仅保存凭证散列
    created_at TEXT NOT NULL
);
CREATE TABLE runtime_save (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL UNIQUE REFERENCES runtime_session(id), -- 一份隔离试玩命卷
    revision INTEGER NOT NULL CHECK(revision >= 0),
    state_json TEXT NOT NULL -- 已确认当前状态投影
);
CREATE TABLE action_request (
    save_id TEXT NOT NULL REFERENCES runtime_save(id),
    request_id TEXT NOT NULL,
    expected_revision INTEGER NOT NULL,
    action TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('PROCESSING','COMMITTED','FAILED')),
    attempt_id TEXT NOT NULL, -- 防止旧执行器提交到重试请求
    error_code TEXT,
    error_message TEXT,
    created_at TEXT NOT NULL,
    PRIMARY KEY(save_id, request_id)
);
-- 同一命卷仅允许一个生成中的请求。
CREATE UNIQUE INDEX one_processing_action ON action_request(save_id) WHERE status = 'PROCESSING';
CREATE TABLE committed_turn (
    save_id TEXT NOT NULL REFERENCES runtime_save(id),
    turn INTEGER NOT NULL,
    request_id TEXT,
    action TEXT NOT NULL,
    candidate_json TEXT NOT NULL, -- 正文、变更原因、事实/传闻/计划分类
    state_json TEXT NOT NULL, -- 当时的已确认投影，旧请求查询不会换成新结果
    references_json TEXT NOT NULL, -- 冻结实际阅读的公共定义与版本
    mode TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY(save_id, turn),
    UNIQUE(save_id, request_id)
);
CREATE TABLE model_call (
    id INTEGER PRIMARY KEY,
    save_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    attempt_id TEXT NOT NULL,
    created_at TEXT NOT NULL -- 持久化模型尝试预算，不随重启重置
);
