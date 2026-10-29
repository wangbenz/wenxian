"""账号命卷用例与持久化边界；归属不从旧试玩凭据推断，列表不解析剧情。"""

import hashlib
import json
import secrets
import uuid

from pydantic import ValidationError

from .domain import GameState, RuntimeProblem
from .identity_domain import token_hash
from .repository import dump, now


class AccountSaveRepository:
    """开卷指纹、归属与索引同事务；旧试玩卷无归属行，不能被账号自动认领。"""

    def __init__(self, repository):
        self.db = repository

    def find_creation(self, account_id, request_id, fingerprint):
        with self.db.connection() as c:
            row = c.execute("SELECT save_id,creation_fingerprint FROM account_save WHERE account_id=? AND creation_request_id=?",
                            (account_id, request_id)).fetchone()
        if row and row["creation_fingerprint"] != fingerprint:
            raise RuntimeProblem("CREATION_CONFLICT", "该开卷请求已确认另一份资料，请先找回原命卷。")
        return row["save_id"] if row else None

    def create(self, account_id, request_id, fingerprint, state, limit, setting=None):
        with self.db.connection(write=True) as c:
            if not c.execute("SELECT 1 FROM identity_account WHERE id=? AND kind='PLAYER' AND active=1", (account_id,)).fetchone():
                raise RuntimeProblem("UNAUTHORIZED", "请重新登录。", 401)
            old = c.execute("SELECT save_id,creation_fingerprint FROM account_save WHERE account_id=? AND creation_request_id=?",
                            (account_id, request_id)).fetchone()
            if old:
                if old["creation_fingerprint"] != fingerprint:
                    raise RuntimeProblem("CREATION_CONFLICT", "该开卷请求已确认另一份资料，请先找回原命卷。")
                return old["save_id"]
            count = c.execute("SELECT COUNT(*) FROM account_save WHERE account_id=?", (account_id,)).fetchone()[0]
            if count >= limit:
                raise RuntimeProblem("SAVE_LIMIT", "命卷位置已满，请先进入已有命卷。")
            save_id = uuid.uuid4().hex
            # 兼容原存储结构的内部会话。随机原文不发给客户端，账号卷不接受旧 Bearer 入口。
            self.db.insert_opening(c, uuid.uuid4().hex, save_id, token_hash(secrets.token_urlsafe(32)), fingerprint, state, setting)
            c.execute("INSERT INTO account_save VALUES (?,?,?,?,?,?,?,?)",
                      (save_id, account_id, request_id, fingerprint, state["name"], state["realm"], state["location"], now()))
            if self.db.generation:
                self.db.generation.opening_grant(c, account_id)
        return save_id

    def summaries(self, account_id):
        with self.db.connection() as c:
            rows = c.execute(
                "SELECT a.save_id AS id,a.name,a.realm,a.location,a.updated_at,s.revision "
                "FROM account_save a JOIN runtime_save s ON s.id=a.save_id WHERE a.account_id=? ORDER BY a.updated_at DESC,a.save_id",
                (account_id,),
            ).fetchall()
        return [dict(row) for row in rows]

    def has_save(self, account_id):
        with self.db.connection() as c:
            return bool(c.execute("SELECT 1 FROM account_save WHERE account_id=? LIMIT 1", (account_id,)).fetchone())

    def owned(self, account_id, save_id):
        with self.db.connection() as c:
            allowed = c.execute("SELECT 1 FROM account_save WHERE account_id=? AND save_id=?", (account_id, save_id)).fetchone()
        if not allowed:
            raise RuntimeProblem("NOT_FOUND", "没有找到这份命卷。", 404)
        return save_id


class AccountSaveService:
    """复用已确认的本地开篇及行动链，不改写 Java 创角或迁移旧玩家数据。"""

    def __init__(self, repository, actions, policy, worlds):
        self.repo = AccountSaveRepository(repository)
        self.actions, self.policy, self.worlds = actions, policy, worlds

    def create(self, account, body):
        character = body.character.model_dump(mode="json", exclude_none=True)
        fingerprint = hashlib.sha256(dump(character).encode()).hexdigest()
        old = self.repo.find_creation(account["id"], body.request_id, fingerprint)
        if old:
            return self.actions.snapshot(old)
        state, setting = self.worlds.prepare(body.character)
        save_id = self.repo.create(account["id"], body.request_id, fingerprint, state.model_dump(mode="json"),
                                   self.policy.maximum_saves, setting)
        return self.actions.snapshot(save_id)

    def summaries(self, account):
        return self.repo.summaries(account["id"])

    def snapshot(self, account, save_id):
        self.owned(account, save_id)
        try:
            snapshot = self.actions.snapshot(save_id)
            GameState.model_validate(snapshot["state"])
            return snapshot
        except (ValueError, json.JSONDecodeError, ValidationError):
            raise RuntimeProblem("SAVE_UNREADABLE", "这份命卷暂时无法读取，记录已保留，其他命卷仍可进入。", 409) from None

    def owned(self, account, save_id):
        return self.repo.owned(account["id"], save_id)

    def reserve(self, account, save_id, body):
        self.owned(account, save_id)
        return self.actions.repo.reserve(save_id, body, account_id=account["id"])
