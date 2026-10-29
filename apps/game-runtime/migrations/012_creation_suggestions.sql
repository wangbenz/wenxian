-- 创角候选独立于命卷事实，幂等请求与模型审计不占用剧情回合。
CREATE TABLE creation_suggestion (
    account_id TEXT NOT NULL REFERENCES identity_account(id), -- 候选所属玩家
    request_id TEXT NOT NULL, -- 玩家操作的幂等编号
    fingerprint TEXT NOT NULL, -- 请求资料散列，防止同号更换资料
    input_json TEXT NOT NULL, -- 玩家填写的创角草稿，不是已发生事实
    context_json TEXT NOT NULL, -- 本次采用的场景与资料快照
    route_json TEXT NOT NULL, -- 冻结的服务端模型路由，不含密钥
    status TEXT NOT NULL CHECK(status IN ('PROCESSING','COMPLETED','FAILED')),
    result_json TEXT, -- 尚未采用的类型化候选
    call_started_at TEXT, -- 非空表示已经预记一次真实调用
    duration_ms INTEGER,
    usage_json TEXT, -- 仅保存 token 用量
    error_code TEXT, -- 固定玩家错误编号
    error_type TEXT, -- 异常类型，不存原文或密钥
    created_at TEXT NOT NULL,
    finished_at TEXT,
    PRIMARY KEY(account_id,request_id)
);
-- 跨标签页保护同一玩家一次只生成一批，失败或完成后可以手动换一批。
CREATE UNIQUE INDEX creation_suggestion_one_pending ON creation_suggestion(account_id) WHERE status='PROCESSING';
