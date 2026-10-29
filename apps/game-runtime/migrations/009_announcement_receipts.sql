-- 公告正文来自只读运行配置；回执绑定正文散列，修改公告后不能沿用旧已读状态。
CREATE TABLE announcement_receipt (
    account_id TEXT NOT NULL REFERENCES identity_account(id),
    release_id TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    read_at INTEGER NOT NULL,
    PRIMARY KEY(account_id,release_id,content_hash)
);
