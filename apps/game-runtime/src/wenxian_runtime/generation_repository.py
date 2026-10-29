"""玩家推演设置/凭证/冻结合同仓储；只写本地玩家数据，密文不回传给 API。"""

from .domain import RuntimeProblem
from .repository import dump


class GenerationRepository:
    def __init__(self, repository):
        self.db = repository

    def preference(self, account_id, connection=None):
        if connection is not None:
            row = connection.execute("SELECT * FROM generation_preference WHERE account_id=?", (account_id,)).fetchone()
            return dict(row) if row else None
        with self.db.connection() as c:
            return self.preference(account_id, c)

    def credentials(self, account_id):
        with self.db.connection() as c:
            rows = c.execute("SELECT id,provider_code,verified_at FROM user_model_credential WHERE account_id=? AND active=1", (account_id,)).fetchall()
        return {row["provider_code"]: dict(row) for row in rows}

    def set_preference(self, account_id, body, timestamp):
        with self.db.connection(write=True) as c:
            if body.mode == "PERSONAL":
                self.active_credential(c, account_id, body.provider)
            c.execute("INSERT INTO generation_preference VALUES (?,?,?,?,?,?) ON CONFLICT(account_id) "
                      "DO UPDATE SET mode=excluded.mode,tier_code=excluded.tier_code,provider_code=excluded.provider_code,model_code=excluded.model_code,updated_at=excluded.updated_at",
                      (account_id, body.mode, body.tierCode, body.provider, body.model, timestamp))

    def save_credential(self, account_id, provider, identifier, encrypted, model, timestamp):
        with self.db.connection(write=True) as c:
            c.execute("UPDATE user_model_credential SET active=0,ciphertext=NULL WHERE account_id=? AND provider_code=?", (account_id, provider))
            c.execute("INSERT INTO user_model_credential VALUES (?,?,?,?,1,?,NULL)", (identifier, account_id, provider, encrypted, timestamp))
            c.execute("INSERT INTO generation_preference VALUES (?,'PERSONAL',NULL,?,?,?) ON CONFLICT(account_id) "
                      "DO UPDATE SET mode='PERSONAL',provider_code=excluded.provider_code,model_code=excluded.model_code,updated_at=excluded.updated_at",
                      (account_id, provider, model, timestamp))

    def delete_credential(self, account_id, provider):
        with self.db.connection(write=True) as c:
            c.execute("UPDATE user_model_credential SET active=0,ciphertext=NULL WHERE account_id=? AND provider_code=?", (account_id, provider))
            # 保留玩家 PERSONAL 选择；缺凭证时明确失败，不静默切平台导致意外扣点。

    @staticmethod
    def active_credential(c, account_id, provider):
        row = c.execute("SELECT id FROM user_model_credential WHERE account_id=? AND provider_code=? AND active=1 AND ciphertext IS NOT NULL", (account_id, provider)).fetchone()
        if not row:
            raise RuntimeProblem("CREDENTIAL_REQUIRED", "请先保存自己的服务密钥。", 409)
        return row["id"]

    def credential(self, account_id, identifier):
        with self.db.connection() as c:
            row = c.execute("SELECT * FROM user_model_credential WHERE id=? AND account_id=? AND active=1 AND ciphertext IS NOT NULL", (identifier, account_id)).fetchone()
        if not row:
            raise RuntimeProblem("CREDENTIAL_CHANGED", "原行动使用的密钥已移除或替换，请保留原结果并重新提交新行动。", 409)
        return dict(row)

    @staticmethod
    def contract(c, request):
        row = c.execute("SELECT * FROM execution_contract WHERE save_id=? AND request_id=?", (request["save_id"], request["request_id"])).fetchone()
        return dict(row) if row else None

    @staticmethod
    def insert_contract(c, request, account_id, mode, points, route, credential_id, timestamp):
        c.execute("INSERT INTO execution_contract VALUES (?,?,?,?,?,?,?,?)",
                  (request["save_id"], request["request_id"], account_id, mode, points, dump(route), credential_id, timestamp))

    def execution(self, request):
        with self.db.connection() as c:
            return self.contract(c, request)
