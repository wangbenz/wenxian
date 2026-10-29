"""玩家推演点持久化：预占/消费/释放与剧情共用写事务，模型请求预算另行记账。"""

import time
import uuid

from .domain import RuntimeProblem


class CreditRepository:
    """按先到期批次分配；失败和旧执行器不能重复释放，成功结果不能重复扣减。"""

    def __init__(self, repository, clock=None):
        self.db, self.clock = repository, clock or time.time

    @staticmethod
    def _entry(c, account_id, grant_id, kind, points, timestamp, request=None):
        save_id, request_id, attempt = (request[key] for key in ("save_id", "request_id", "attempt_id")) if request else (None, None, None)
        event = f"{kind}:{grant_id}:{save_id}:{request_id}:{attempt}"
        c.execute("INSERT INTO credit_ledger(event_key,account_id,grant_id,save_id,request_id,attempt_id,kind,points,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                  (event, account_id, grant_id, save_id, request_id, attempt, kind, points, timestamp))

    def grant(self, c, account_id, source, amount, expires_at=None):
        """仅供已确认配置的开卷赠送与隔离验证调用，不提供运营写 API。"""
        existing = c.execute("SELECT id FROM credit_grant WHERE account_id=? AND source=?", (account_id, source)).fetchone()
        if existing:
            return existing["id"]
        identifier, timestamp = uuid.uuid4().hex, int(self.clock())
        c.execute("INSERT INTO credit_grant(id,account_id,source,amount,expires_at,created_at) VALUES (?,?,?,?,?,?)",
                  (identifier, account_id, source, amount, expires_at, timestamp))
        self._entry(c, account_id, identifier, "GRANT", amount, timestamp)
        return identifier

    def reserve(self, c, request, account_id, points):
        timestamp = int(self.clock())
        old = c.execute("SELECT * FROM credit_reservation WHERE save_id=? AND request_id=?",
                        (request["save_id"], request["request_id"])).fetchone()
        if old and (old["status"] != "RELEASED" or old["points"] != points or old["account_id"] != account_id):
            raise RuntimeProblem("CREDIT_STATE_CONFLICT", "原请求额度状态待确认，请先查询原结果。")
        grants = c.execute(
            "SELECT id,amount-reserved-spent AS available FROM credit_grant WHERE account_id=? "
            "AND (expires_at IS NULL OR expires_at>?) AND amount>reserved+spent "
            "ORDER BY expires_at IS NULL,expires_at,created_at,id", (account_id, timestamp),
        ).fetchall()
        if sum(row["available"] for row in grants) < points:
            raise RuntimeProblem("TURN_LIMIT", "可用推演点不足，原行动已保留。可在设置中选择自己的续写服务。", 409)
        c.execute(
            "INSERT INTO credit_reservation VALUES (?,?,?,?,?,'RESERVED',?,?) "
            "ON CONFLICT(save_id,request_id) DO UPDATE SET attempt_id=excluded.attempt_id,status='RESERVED',updated_at=excluded.updated_at",
            (request["save_id"], request["request_id"], account_id, request["attempt_id"], points, timestamp, timestamp),
        )
        remaining = points
        for row in grants:
            allocation = min(remaining, row["available"])
            if not allocation:
                break
            c.execute("UPDATE credit_grant SET reserved=reserved+? WHERE id=?", (allocation, row["id"]))
            c.execute("INSERT INTO credit_allocation VALUES (?,?,?,?,?)",
                      (request["save_id"], request["request_id"], request["attempt_id"], row["id"], allocation))
            self._entry(c, account_id, row["id"], "RESERVE", allocation, timestamp, request)
            remaining -= allocation

    def finish(self, c, request, success):
        row = c.execute(
            "SELECT * FROM credit_reservation WHERE save_id=? AND request_id=? AND attempt_id=? AND status='RESERVED'",
            (request["save_id"], request["request_id"], request["attempt_id"]),
        ).fetchone()
        if not row:
            # 旧试玩没有玩家账本；已终结/旧 attempt 不重复结算。
            return
        timestamp = int(self.clock())
        allocations = c.execute("SELECT * FROM credit_allocation WHERE save_id=? AND request_id=? AND attempt_id=?",
                                (request["save_id"], request["request_id"], request["attempt_id"])).fetchall()
        if sum(item["points"] for item in allocations) != row["points"]:
            raise RuntimeProblem("CREDIT_STATE_CONFLICT", "额度记录需要核对，本回合尚未提交。")
        for allocation in allocations:
            points = allocation["points"]
            changed = c.execute("UPDATE credit_grant SET reserved=reserved-?,spent=spent+? WHERE id=? AND account_id=? AND reserved>=?",
                                (points, points if success else 0, allocation["grant_id"], row["account_id"], points)).rowcount
            if changed != 1:
                raise RuntimeProblem("CREDIT_STATE_CONFLICT", "额度记录需要核对，本回合尚未提交。")
            self._entry(c, row["account_id"], allocation["grant_id"], "SPEND" if success else "RELEASE", points, timestamp, request)
        c.execute("UPDATE credit_reservation SET status=?,updated_at=? WHERE save_id=? AND request_id=?",
                  ("SPENT" if success else "RELEASED", timestamp, request["save_id"], request["request_id"]))

    def balance(self, account_id):
        timestamp = int(self.clock())
        with self.db.connection() as c:
            rows = c.execute("SELECT amount,reserved,spent,expires_at FROM credit_grant WHERE account_id=?", (account_id,)).fetchall()
        return {
            "granted": sum(row["amount"] for row in rows), "spent": sum(row["spent"] for row in rows),
            "reserved": sum(row["reserved"] for row in rows),
            "available": sum(row["amount"]-row["reserved"]-row["spent"] for row in rows if row["expires_at"] is None or row["expires_at"] > timestamp),
            "expired": sum(row["amount"]-row["reserved"]-row["spent"] for row in rows if row["expires_at"] is not None and row["expires_at"] <= timestamp),
        }

    def ledger(self, account_id, before=None, limit=50):
        with self.db.connection() as c:
            rows = c.execute("SELECT id,grant_id,save_id,request_id,kind,points,created_at FROM credit_ledger "
                             "WHERE account_id=? AND id<? ORDER BY id DESC LIMIT ?", (account_id, before or 2**63-1, min(max(limit, 1), 100))).fetchall()
        return [dict(row) for row in rows]
