"""创角候选仓储：幂等、账号归属、单批并发和调用审计；不读写命卷事实或点数。"""

import json
import sqlite3

from .domain import RuntimeProblem
from .repository import dump, now


class CreationRepository:
    """模型执行在短事务之外；同编号重复查询不重发，处理中断后只能显式生成新批次。"""

    def __init__(self, repository):
        self.db = repository

    def find(self, account_id, request_id, fingerprint=None):
        with self.db.connection() as c:
            row = c.execute("SELECT * FROM creation_suggestion WHERE account_id=? AND request_id=?",
                            (account_id, request_id)).fetchone()
        if row and fingerprint and row["fingerprint"] != fingerprint:
            raise RuntimeProblem("SUGGESTION_CONFLICT", "这批候选的资料已确认，请使用原资料查看或重新生成。", 409)
        return dict(row) if row else None

    def reserve(self, account_id, body, fingerprint, context, route):
        with self.db.connection(write=True) as c:
            try:
                c.execute("INSERT INTO creation_suggestion(account_id,request_id,fingerprint,input_json,context_json,"
                          "route_json,status,created_at) VALUES (?,?,?,?,?,?,'PROCESSING',?)",
                          (account_id, body.request_id, fingerprint, dump(body.model_dump()), dump(context), dump(route), now()))
            except sqlite3.IntegrityError:
                row = c.execute("SELECT * FROM creation_suggestion WHERE account_id=? AND request_id=?",
                                (account_id, body.request_id)).fetchone()
                if row and row["fingerprint"] == fingerprint:
                    return dict(row), False
                if row:
                    raise RuntimeProblem("SUGGESTION_CONFLICT", "这批候选的资料已确认，请重新生成。", 409) from None
                raise RuntimeProblem("SUGGESTION_BUSY", "上一批候选还在生成，请稍候查看。", 409) from None
        return self.find(account_id, body.request_id), True

    def start_call(self, account_id, request_id, limit):
        with self.db.connection(write=True) as c:
            row = c.execute("SELECT * FROM creation_suggestion WHERE account_id=? AND request_id=?",
                            (account_id, request_id)).fetchone()
            if not row or row["status"] != "PROCESSING" or row["call_started_at"]:
                return False
            if limit is not None and self.db.model_call_total(c) >= limit:
                raise RuntimeProblem("MODEL_BUDGET", "本地模型调用额度暂不可用，填写内容已保留。", 503)
            c.execute("UPDATE creation_suggestion SET call_started_at=? WHERE account_id=? AND request_id=?",
                      (now(), account_id, request_id))
            return True

    def finish(self, account_id, request_id, *, result=None, duration_ms=0, usage=None, error_code=None, error_type=None):
        safe_usage = {key: value for key, value in (usage or {}).items()
                      if key in {"input_tokens", "output_tokens", "total_tokens"} and isinstance(value, int)}
        with self.db.connection(write=True) as c:
            c.execute("UPDATE creation_suggestion SET status=?,result_json=?,duration_ms=?,usage_json=?,error_code=?,"
                      "error_type=?,finished_at=? WHERE account_id=? AND request_id=? AND status='PROCESSING'",
                      ("FAILED" if error_code else "COMPLETED", dump(result) if result else None, duration_ms,
                       dump(safe_usage), error_code, error_type, now(), account_id, request_id))

    @staticmethod
    def view(row):
        error = None
        if row["status"] == "FAILED":
            error = {"code": row["error_code"], "message": "这批候选未能生成，填写内容已保留，可以手动重试。"}
        return {"request_id": row["request_id"], "status": row["status"], "kind": json.loads(row["input_json"])["kind"],
                "result": json.loads(row["result_json"]) if row["result_json"] else None, "error": error}
