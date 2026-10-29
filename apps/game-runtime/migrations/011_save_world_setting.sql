-- 每份新命卷固定一次资料设定及开局前提；旧命卷不回填、不改写历史。
CREATE TABLE save_world_setting (
    save_id TEXT PRIMARY KEY REFERENCES runtime_save(id),
    profile_id TEXT NOT NULL, -- 服务端选择的场景设定编号
    setting_hash TEXT NOT NULL CHECK(length(setting_hash)=64), -- 完整设定快照的散列
    setting_json TEXT NOT NULL, -- 有格式版本的公共定义及本局场景快照
    created_at TEXT NOT NULL
);
