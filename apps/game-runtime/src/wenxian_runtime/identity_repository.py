"""身份持久化：短事务内保护唯一身份、会话用途、限流和密码版本，不读取剧情。"""

import secrets
import uuid

from .domain import RuntimeProblem


class IdentityRepository:
    """共享独立 SQLite 的连接管理；认证失败也必须提交限流记录，秘密不进入审计。"""

    def __init__(self, repository, policy):
        self.db, self.policy = repository, policy

    def pepper(self):
        with self.db.connection(write=True) as c:
            c.execute("INSERT OR IGNORE INTO runtime_private_config VALUES ('identity_pepper',?)", (secrets.token_hex(32),))
            return c.execute("SELECT value FROM runtime_private_config WHERE key='identity_pepper'").fetchone()[0]

    def reserve_login(self, kind, login, subject, client, timestamp):
        scope = f"login:{kind}"
        with self.db.connection(write=True) as c:
            since = timestamp - self.policy.failure_window
            subject_count = c.execute(
                "SELECT COUNT(*) FROM identity_attempt WHERE scope=? AND subject_hash=? AND occurred_at>? AND success=0",
                (scope, subject, since),
            ).fetchone()[0]
            client_count = c.execute(
                "SELECT COUNT(*) FROM identity_attempt WHERE scope=? AND client_hash=? AND occurred_at>? AND success=0",
                (scope, client, since),
            ).fetchone()[0]
            if subject_count >= self.policy.failures_per_subject or client_count >= self.policy.failures_per_client:
                raise RuntimeProblem("AUTH_RATE_LIMITED", "尝试次数较多，请稍后再试。", 429)
            attempt = c.execute(
                "INSERT INTO identity_attempt(scope,subject_hash,client_hash,occurred_at,success) VALUES (?,?,?,?,0)",
                (scope, subject, client, timestamp),
            ).lastrowid
            row = c.execute("SELECT * FROM identity_account WHERE kind=? AND login=?", (kind, login)).fetchone()
        return (dict(row) if row else None), attempt

    def _session(self, c, account, digest, timestamp):
        seconds = self.policy.session_seconds
        c.execute("INSERT INTO identity_session VALUES (?,?,?,?,?,NULL)",
                  (digest, account["id"], timestamp, timestamp + seconds, timestamp))

    def finish_login(self, account, attempt, digest, timestamp):
        with self.db.connection(write=True) as c:
            row = c.execute("SELECT * FROM identity_account WHERE id=? AND active=1 AND password_hash=?",
                            (account["id"], account["password_hash"])).fetchone()
            if not row:
                raise RuntimeProblem("LOGIN_FAILED", "账号或密码不正确，请重新登录。", 401)
            self._session(c, row, digest, timestamp)
            c.execute("UPDATE identity_attempt SET success=1 WHERE id=?", (attempt,))

    def register(self, login, encoded, digest, client, timestamp):
        with self.db.connection(write=True) as c:
            count = c.execute("SELECT COUNT(*) FROM identity_account WHERE kind='PLAYER'").fetchone()[0]
            attempts = c.execute(
                "SELECT COUNT(*) FROM identity_attempt WHERE scope='register' AND client_hash=? AND occurred_at>?",
                (client, timestamp - 86400),
            ).fetchone()[0]
            if count >= self.policy.maximum_players or attempts >= self.policy.registrations_per_client:
                raise RuntimeProblem("REGISTRATION_LIMIT", "当前注册名额已满，请稍后再试。", 429)
            if c.execute("SELECT 1 FROM identity_account WHERE kind='PLAYER' AND login=?", (login,)).fetchone():
                raise RuntimeProblem("ACCOUNT_EXISTS", "无法重复注册，请使用已有账号登录。", 409)
            account = {"id": uuid.uuid4().hex, "kind": "PLAYER", "login": login, "role": "PLAYER"}
            c.execute("INSERT INTO identity_account VALUES (?,?,?,?,?,1,?,?)",
                      (account["id"], "PLAYER", login, encoded, "PLAYER", timestamp, timestamp))
            self._session(c, account, digest, timestamp)
            c.execute("INSERT INTO identity_attempt(scope,subject_hash,client_hash,occurred_at,success) VALUES ('register','',?,?,1)",
                      (client, timestamp))
        return account

    def authenticate(self, digest, kind, timestamp, touch=True):
        # 闲置时间刷新与撤销检查同事务，不把陈旧身份重新激活。
        with self.db.connection(write=touch) as c:
            row = c.execute(
                "SELECT a.* FROM identity_session s JOIN identity_account a ON a.id=s.account_id "
                "WHERE s.token_hash=? AND a.kind=? AND a.active=1 AND s.revoked_at IS NULL "
                "AND s.expires_at>? AND s.last_seen_at>?",
                (digest, kind, timestamp, timestamp - self.policy.idle_seconds),
            ).fetchone()
            if row and touch:
                c.execute("UPDATE identity_session SET last_seen_at=? WHERE token_hash=?", (timestamp, digest))
        return dict(row) if row else None

    def logout(self, digest, kind, timestamp):
        with self.db.connection(write=True) as c:
            c.execute("UPDATE identity_session SET revoked_at=? WHERE token_hash=? AND account_id IN "
                      "(SELECT id FROM identity_account WHERE kind=?) AND revoked_at IS NULL", (timestamp, digest, kind))

    def change_password(self, account, old_digest, encoded, new_digest, timestamp, attempt):
        with self.db.connection(write=True) as c:
            valid = c.execute(
                "SELECT 1 FROM identity_session s JOIN identity_account a ON s.account_id=a.id "
                "WHERE s.token_hash=? AND a.id=? AND a.active=1 AND a.password_hash=? "
                "AND s.revoked_at IS NULL AND s.expires_at>? AND s.last_seen_at>?",
                (old_digest, account["id"], account["password_hash"], timestamp, timestamp-self.policy.idle_seconds),
            ).fetchone()
            if not valid:
                raise RuntimeProblem("UNAUTHORIZED", "登录状态已变化，请重新登录。", 401)
            c.execute("UPDATE identity_account SET password_hash=?,password_changed_at=? WHERE id=?",
                      (encoded, timestamp, account["id"]))
            c.execute("UPDATE identity_session SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", (timestamp, account["id"]))
            if new_digest:
                self._session(c, account, new_digest, timestamp)
            c.execute("UPDATE identity_attempt SET success=1 WHERE id=?", (attempt,))
            self._audit(c, account["id"], "PASSWORD_CHANGED", account["id"], timestamp)

    @staticmethod
    def _audit(c, actor, operation, target, timestamp):
        c.execute("INSERT INTO identity_security_audit(actor_id,operation,target_id,created_at) VALUES (?,?,?,?)", (actor, operation, target, timestamp))
