"""SQLite 持久化与显式迁移；只保护权限、事务和版本，不裁决开放剧情。"""

import hashlib
import json
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from .domain import ActionInput, Candidate, GameState, RuntimeProblem

ROOT = Path(__file__).resolve().parents[2]


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def dump(value) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


class Repository:
    """每次操作独立连接，写入短事务；生成在事务之外，提交使用 revision 和 attempt CAS。"""

    def __init__(self, path: Path):
        self.path = path
        self.generation = None

    @contextmanager
    def connection(self, write=False):
        conn = sqlite3.connect(self.path, timeout=10)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        try:
            if write:
                conn.execute("BEGIN IMMEDIATE")
            yield conn
            conn.commit()
        except BaseException:
            conn.rollback()
            raise
        finally:
            conn.close()

    def migrate(self):
        """迁移标记和 DDL 同事务，不靠 IF NOT EXISTS 假装升级已有表。"""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection(write=True) as c:
            c.execute("CREATE TABLE IF NOT EXISTS schema_migration (name TEXT PRIMARY KEY)")
            for script in sorted((ROOT / "migrations").glob("*.sql")):
                if c.execute("SELECT 1 FROM schema_migration WHERE name=?", (script.name,)).fetchone():
                    continue
                # 每条 SQL 以分号结束；本模块迁移不含 trigger/字符串内分号。
                for statement in script.read_text().split(";"):
                    if statement.strip():
                        c.execute(statement)
                c.execute("INSERT INTO schema_migration VALUES (?)", (script.name,))

    def recover_interrupted(self):
        """仅独占运行时锁后调用；停机中断的请求失败可重试，不自动重放模型。"""
        with self.connection(write=True) as c:
            c.execute("UPDATE creation_suggestion SET status='FAILED',error_code='INTERRUPTED',finished_at=? "
                      "WHERE status='PROCESSING'", (now(),))
            interrupted = c.execute("SELECT * FROM action_request WHERE status='PROCESSING'").fetchall()
            c.execute(
                "UPDATE action_request SET status='FAILED', error_code='INTERRUPTED', error_message=? WHERE status='PROCESSING'",
                ("续写因服务中断未完成，原行动已保留，可原样重试。",),
            )
            if self.generation:
                for request in interrupted:
                    self.generation.finish(c, dict(request), False)

    def session_creation(self, token: str, fingerprint: str) -> str | None:
        """先找回同凭据开卷结果，资料库不可用也不阻止已有创建请求恢复。"""
        with self.connection() as c:
            row = c.execute(
                "SELECT s.id,u.creation_fingerprint FROM runtime_session u JOIN runtime_save s ON s.session_id=u.id "
                "WHERE u.token_hash=? AND NOT EXISTS (SELECT 1 FROM account_save a WHERE a.save_id=s.id)",
                (hashlib.sha256(token.encode()).hexdigest(),),
            ).fetchone()
        if row and row["creation_fingerprint"] != fingerprint:
            raise RuntimeProblem("CREATION_CONFLICT", "这次开卷的资料已确认，请先找回原命卷。")
        return row["id"] if row else None

    def create_session(self, token: str, state: GameState, fingerprint: str, setting=None) -> str:
        """同一随机会话凭据只创建一次；资料冲突拒绝，开局与会话同事务保存。"""
        session_id, save_id = uuid.uuid4().hex, uuid.uuid4().hex
        token_hash = hashlib.sha256(token.encode()).hexdigest()
        state = state.model_dump(mode="json")
        with self.connection(write=True) as c:
            existing = c.execute(
                "SELECT s.id,u.creation_fingerprint FROM runtime_session u JOIN runtime_save s ON s.session_id=u.id WHERE u.token_hash=?",
                (token_hash,),
            ).fetchone()
            if existing:
                if existing["creation_fingerprint"] != fingerprint:
                    raise RuntimeProblem("CREATION_CONFLICT", "这次开卷的资料已确认，请先找回原命卷。")
                return existing["id"]
            self.insert_opening(c, session_id, save_id, token_hash, fingerprint, state, setting)
        return save_id

    @staticmethod
    def insert_opening(c, session_id, save_id, digest, fingerprint, state, setting=None):
        """调用者持有写事务；创建会话、命卷和开篇，不提交或调用模型。"""
        c.execute("INSERT INTO runtime_session(id,token_hash,created_at,creation_fingerprint) VALUES (?,?,?,?)",
                  (session_id, digest, now(), fingerprint))
        c.execute("INSERT INTO runtime_save VALUES (?,?,0,?)", (save_id, session_id, dump(state)))
        c.execute(
            "INSERT INTO committed_turn(save_id,turn,request_id,action,candidate_json,state_json,references_json,mode,created_at) VALUES (?,0,NULL,?,?,?,?,?,?)",
            (save_id, "开局", dump({"scene_title": state["scene_title"], "narrative": state["narrative"], "information": []}),
             dump(state), "[]", "opening", now()),
        )
        if setting is not None:
            c.execute("INSERT INTO save_world_setting VALUES (?,?,?,?,?)",
                      (save_id, setting.profile.id, setting.digest(), setting.model_dump_json(), now()))

    def world_setting(self, save_id):
        """只读本卷设定；未升级旧证据库和未绑定旧卷返回缺失，不创建或回填。"""
        with self.connection() as c:
            if not c.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='save_world_setting'").fetchone():
                return None
            row = c.execute("SELECT setting_hash,setting_json FROM save_world_setting WHERE save_id=?", (save_id,)).fetchone()
        return dict(row) if row else None

    def summary(self, save_id: str) -> dict:
        """列表只读取名称、地点与回合投影，不装载正文、资产或整段历史。"""
        with self.connection() as c:
            row = c.execute(
                "SELECT s.id,s.revision,json_extract(s.state_json,'$.name') AS name,"
                "json_extract(s.state_json,'$.realm') AS realm,"
                "json_extract(s.state_json,'$.location') AS location,"
                "(SELECT created_at FROM committed_turn WHERE save_id=s.id ORDER BY turn DESC LIMIT 1) AS updated_at "
                "FROM runtime_save s WHERE s.id=?",
                (save_id,),
            ).fetchone()
        if not row:
            raise RuntimeProblem("NOT_FOUND", "没有找到这份试玩命卷。", 404)
        return dict(row)

    def owner_save(self, token: str) -> str:
        with self.connection() as c:
            row = c.execute(
                "SELECT s.id FROM runtime_save s JOIN runtime_session u ON u.id=s.session_id "
                "WHERE u.token_hash=? AND NOT EXISTS (SELECT 1 FROM account_save a WHERE a.save_id=s.id)",
                (hashlib.sha256(token.encode()).hexdigest(),),
            ).fetchone()
        if not row:
            raise RuntimeProblem("UNAUTHORIZED", "这份试玩的会话已无法使用，请重新进入。", 401)
        return row["id"]

    def save(self, save_id: str) -> dict:
        with self.connection() as c:
            row = c.execute("SELECT * FROM runtime_save WHERE id=?", (save_id,)).fetchone()
        if not row:
            raise RuntimeProblem("NOT_FOUND", "没有找到这份试玩命卷。", 404)
        return {"save_id": row["id"], "revision": row["revision"], "state": json.loads(row["state_json"])}

    def history(self, save_id: str, before: int | None = None, limit: int = 3) -> list[dict]:
        limit = max(1, min(limit, 20))
        with self.connection() as c:
            rows = c.execute(
                "SELECT turn,action,candidate_json,references_json,mode FROM committed_turn WHERE save_id=? AND turn<? ORDER BY turn DESC LIMIT ?",
                (save_id, before if before is not None else 2**63 - 1, limit),
            ).fetchall()
        return [
            {
                "turn": row["turn"],
                "action": row["action"],
                "result": json.loads(row["candidate_json"]),
                "references": json.loads(row["references_json"]),
                "mode": row["mode"],
            }
            for row in reversed(rows)
        ]

    def definition_pins(self, save_id: str) -> dict:
        """查阅只需要本局资料版本，不提前装载行囊、人物和完整正文。"""
        with self.connection() as c:
            row = c.execute(
                "SELECT json_extract(state_json,'$.definition_pins') FROM runtime_save WHERE id=?",
                (save_id,),
            ).fetchone()
        if not row:
            raise RuntimeProblem("NOT_FOUND", "没有找到这份试玩命卷。", 404)
        return json.loads(row[0]) if row[0] else {}

    def reserve(self, save_id: str, action: ActionInput, account_id: str | None = None) -> tuple[dict, bool]:
        """重复输入复用原请求；失败请求才允许同输入、同版本重试。"""
        with self.connection(write=True) as c:
            owner = c.execute("SELECT account_id FROM account_save WHERE save_id=?", (save_id,)).fetchone()
            if (owner and owner["account_id"] != account_id) or (account_id and not owner):
                raise RuntimeProblem("NOT_FOUND", "没有找到这份命卷。", 404)
            save = c.execute("SELECT revision FROM runtime_save WHERE id=?", (save_id,)).fetchone()
            if not save:
                raise RuntimeProblem("NOT_FOUND", "没有找到这份命卷。", 404)
            old = c.execute(
                "SELECT * FROM action_request WHERE save_id=? AND request_id=?", (save_id, action.request_id)
            ).fetchone()
            if old:
                if old["action"] != action.action or old["expected_revision"] != action.expected_revision:
                    raise RuntimeProblem("REQUEST_CONFLICT", "原行动编号已用于另一份内容，请先确认原结果。")
                if old["status"] != "FAILED":
                    return dict(old), False
            if save["revision"] != action.expected_revision:
                raise RuntimeProblem("REVISION_CONFLICT", "命卷已有新的结果，请先刷新后再行动。")
            if c.execute(
                "SELECT 1 FROM action_request WHERE save_id=? AND status='PROCESSING'", (save_id,)
            ).fetchone():
                raise RuntimeProblem("ACTION_IN_PROGRESS", "已有行动正在续写，请先确认它的结果。")
            if account_id and c.execute(
                "SELECT 1 FROM action_request r JOIN account_save a ON a.save_id=r.save_id "
                "WHERE a.account_id=? AND r.status='PROCESSING'", (account_id,),
            ).fetchone():
                raise RuntimeProblem("ACTION_IN_PROGRESS", "另一份命卷正在续写，请先确认它的结果。")
            attempt = uuid.uuid4().hex
            if old:
                c.execute(
                    "UPDATE action_request SET status='PROCESSING',attempt_id=?,error_code=NULL,error_message=NULL WHERE save_id=? AND request_id=?",
                    (attempt, save_id, action.request_id),
                )
            else:
                c.execute(
                    "INSERT INTO action_request VALUES (?,?,?,?, 'PROCESSING', ?,NULL,NULL,?)",
                    (save_id, action.request_id, action.expected_revision, action.action, attempt, now()),
                )
            row = c.execute(
                "SELECT * FROM action_request WHERE save_id=? AND request_id=?", (save_id, action.request_id)
            ).fetchone()
            if account_id and self.generation:
                self.generation.admit(c, dict(row), account_id)
        return dict(row), True

    def status(self, save_id: str, request_id: str) -> dict:
        with self.connection() as c:
            row = c.execute(
                "SELECT * FROM action_request WHERE save_id=? AND request_id=?", (save_id, request_id)
            ).fetchone()
            if not row:
                return {"status": "UNKNOWN", "request_id": request_id}
            response = {
                "status": row["status"],
                "request_id": request_id,
                "action": row["action"],
                "expected_revision": row["expected_revision"],
                "error": None,
            }
            if row["status"] == "FAILED":
                response["error"] = {"code": row["error_code"], "message": row["error_message"]}
            if row["status"] == "COMMITTED":
                turn = c.execute(
                    "SELECT turn,state_json FROM committed_turn WHERE save_id=? AND request_id=?",
                    (save_id, request_id),
                ).fetchone()
                response["snapshot"] = {
                    "save_id": save_id,
                    "revision": turn["turn"],
                    "state": json.loads(turn["state_json"]),
                }
        return response

    def fail(self, request: dict, code: str, message: str):
        with self.connection(write=True) as c:
            changed = c.execute(
                "UPDATE action_request SET status='FAILED',error_code=?,error_message=? WHERE save_id=? AND request_id=? AND attempt_id=? AND status='PROCESSING'",
                (code, message, request["save_id"], request["request_id"], request["attempt_id"]),
            ).rowcount
            if changed and self.generation:
                self.generation.finish(c, request, False)

    def active_request(self, save_id: str) -> dict | None:
        with self.connection() as c:
            row = c.execute(
                "SELECT request_id,expected_revision,action FROM action_request WHERE save_id=? AND status='PROCESSING'",
                (save_id,),
            ).fetchone()
        return dict(row) if row else None

    def ensure_active(self, request: dict):
        with self.connection() as c:
            row = c.execute(
                "SELECT status,attempt_id FROM action_request WHERE save_id=? AND request_id=?",
                (request["save_id"], request["request_id"]),
            ).fetchone()
        if not row or row["status"] != "PROCESSING" or row["attempt_id"] != request["attempt_id"]:
            raise RuntimeProblem("STALE_ATTEMPT", "原请求状态已改变，请查询当前结果。")

    def has_simulated_history(self, save_id: str) -> bool:
        with self.connection() as c:
            return bool(
                c.execute(
                    "SELECT 1 FROM committed_turn WHERE save_id=? AND mode='fixture' LIMIT 1", (save_id,)
                ).fetchone()
            )

    def commit(
        self,
        request: dict,
        candidate: Candidate,
        state: GameState,
        references: list[dict],
        mode: str,
        audit: dict | None = None,
    ):
        with self.connection(write=True) as c:
            active = c.execute(
                "SELECT status,attempt_id FROM action_request WHERE save_id=? AND request_id=?",
                (request["save_id"], request["request_id"]),
            ).fetchone()
            if active["status"] != "PROCESSING" or active["attempt_id"] != request["attempt_id"]:
                raise RuntimeProblem("STALE_ATTEMPT", "原请求状态已改变，请查询当前结果。")
            revision = request["expected_revision"] + 1
            encoded_state = state.model_dump_json()
            count = c.execute(
                "UPDATE runtime_save SET revision=?,state_json=? WHERE id=? AND revision=?",
                (revision, encoded_state, request["save_id"], request["expected_revision"]),
            ).rowcount
            if count != 1:
                raise RuntimeProblem("REVISION_CONFLICT", "命卷已有新结果，请先确认。")
            c.execute("UPDATE account_save SET name=?,realm=?,location=?,updated_at=? WHERE save_id=?",
                      (state.name, state.realm, state.location, now(), request["save_id"]))
            c.execute(
                "INSERT INTO committed_turn(save_id,turn,request_id,action,candidate_json,state_json,references_json,mode,created_at,generation_audit_json) VALUES (?,?,?,?,?,?,?,?,?,?)",
                (
                    request["save_id"],
                    revision,
                    request["request_id"],
                    request["action"],
                    candidate.model_dump_json(),
                    encoded_state,
                    dump(references),
                    mode,
                    now(),
                    dump(audit or {}),
                ),
            )
            c.execute(
                "UPDATE action_request SET status='COMMITTED' WHERE save_id=? AND request_id=?",
                (request["save_id"], request["request_id"]),
            )
            if self.generation:
                self.generation.finish(c, request, True)

    @staticmethod
    def model_call_total(c):
        """只汇总已预记的提供商请求；创角候选不属于剧情回合，但同受本机总调用门禁保护。"""
        return c.execute("SELECT (SELECT COUNT(*) FROM model_call) + "
                         "(SELECT COUNT(*) FROM creation_suggestion WHERE call_started_at IS NOT NULL)").fetchone()[0]

    def spend_model_call(self, request: dict, total_limit: int | None, per_attempt_limit: int = 4):
        """调用前持久化计数；显式 None 不限总次数，仍检查原尝试及单次工具往返，不承担玩家额度扣减。"""
        with self.connection(write=True) as c:
            active = c.execute(
                "SELECT status,attempt_id FROM action_request WHERE save_id=? AND request_id=?",
                (request["save_id"], request["request_id"]),
            ).fetchone()
            if (
                not active
                or active["status"] != "PROCESSING"
                or active["attempt_id"] != request["attempt_id"]
            ):
                raise RuntimeProblem("STALE_ATTEMPT", "原请求状态已改变，请查询当前结果。")
            if self.generation:
                self.generation.assert_credential_active(c, request)
            total = self.model_call_total(c)
            turn = c.execute(
                "SELECT COUNT(*) FROM model_call WHERE save_id=? AND request_id=? AND attempt_id=?",
                (request["save_id"], request["request_id"], request["attempt_id"]),
            ).fetchone()[0]
            if (total_limit is not None and total >= total_limit) or turn >= per_attempt_limit:
                raise RuntimeProblem("MODEL_BUDGET", "本轮试玩调用额度已用完，原行动已保留。", 503)
            cursor = c.execute(
                "INSERT INTO model_call(save_id,request_id,attempt_id,created_at) VALUES (?,?,?,?)",
                (request["save_id"], request["request_id"], request["attempt_id"], now()),
            )
            return cursor.lastrowid

    def record_model_call(
        self,
        call_id: int,
        duration_ms: int,
        usage: dict | None = None,
        error_type: str | None = None,
        provider_status: int | None = None,
        validation_errors: list[dict] | None = None,
        response_shape: dict | None = None,
    ):
        """只存数值用量、异常类型与 HTTP 状态，不保存认证头、提供商异常正文或思考正文。"""
        safe_usage = {
            key: value
            for key, value in (usage or {}).items()
            if key in {"input_tokens", "output_tokens", "total_tokens"}
            and isinstance(value, int)
            and value >= 0
        }
        with self.connection(write=True) as connection:
            connection.execute(
                "UPDATE model_call SET outcome=?,duration_ms=?,usage_json=?,error_type=?,provider_status=?,completed_at=?,validation_errors_json=?,response_shape_json=? WHERE id=? AND outcome='RESERVED'",
                (
                    "FAILED" if error_type else "SUCCESS",
                    max(0, duration_ms),
                    dump(safe_usage),
                    error_type,
                    provider_status,
                    now(),
                    dump(validation_errors or []),
                    dump(response_shape or {}),
                    call_id,
                ),
            )
