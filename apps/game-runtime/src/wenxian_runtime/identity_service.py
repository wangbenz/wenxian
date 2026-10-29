"""账号应用服务：验证密码、验证玩家权限并创建会话；失败不读取或变更剧情。"""

import hashlib
import hmac
import secrets
import time

from .domain import RuntimeProblem
from .identity_domain import mask_email, password_hash, password_matches, token_hash
from .identity_repository import IdentityRepository


class IdentityService:
    """令牌只在登录时短暂返回；认证、限流和密码版本依靠持久记录。"""

    def __init__(self, repository, policy, clock=None):
        self.repo = IdentityRepository(repository, policy)
        self.policy = policy
        self.clock = clock or time.time
        self._pepper = None
        # 不存在账号也执行相同密码派生，避免快速失败泄露账号存在性。
        self._dummy = password_hash(secrets.token_urlsafe(32))

    def digest(self, value):
        if self._pepper is None:
            self._pepper = self.repo.pepper().encode()
        return hmac.new(self._pepper, value.encode(), hashlib.sha256).hexdigest()

    def login(self, kind, login, password, client):
        if kind != "PLAYER":
            raise RuntimeProblem("UNAUTHORIZED", "请使用玩家账号登录。", 401)
        login = login.lower()
        account, attempt = self.repo.reserve_login(kind, login, self.digest(f"{kind}:{login}"), self.digest(client), int(self.clock()))
        valid = password_matches(password, account["password_hash"] if account else self._dummy)
        if not valid or not account or not account["active"]:
            raise RuntimeProblem("LOGIN_FAILED", "账号或密码不正确。", 401)
        token = secrets.token_urlsafe(32)
        self.repo.finish_login(account, attempt, token_hash(token), int(self.clock()))
        return account, token

    def register(self, login, password, client):
        token = secrets.token_urlsafe(32)
        account = self.repo.register(login, password_hash(password), token_hash(token), self.digest(client), int(self.clock()))
        return account, token

    def principal(self, token, kind, required=True):
        account = self.repo.authenticate(token_hash(token), "PLAYER", int(self.clock())) if kind == "PLAYER" and token and len(token) <= 128 else None
        if not account and required:
            raise RuntimeProblem("UNAUTHORIZED", "请重新登录后继续。", 401)
        return account

    def logout(self, token, kind):
        if token:
            self.repo.logout(token_hash(token), kind, int(self.clock()))

    def change_password(self, token, kind, old_password, new_password, client):
        account = self.principal(token, kind)
        # 旧密码同样限流，防止已登录设备无限猜测。
        _, attempt = self.repo.reserve_login(f"password:{kind}", account["login"], self.digest(account["id"]), self.digest(client), int(self.clock()))
        if not password_matches(old_password, account["password_hash"]):
            raise RuntimeProblem("PASSWORD_MISMATCH", "当前密码不正确。", 400)
        new_token = secrets.token_urlsafe(32)
        self.repo.change_password(account, token_hash(token), password_hash(new_password),
                                  token_hash(new_token) if new_token else None, int(self.clock()), attempt)
        return account, new_token

    @staticmethod
    def player_view(account, has_save=False, new_user=False):
        return {"authenticated": bool(account), "newUser": new_user,
                "user": {"userId": account["id"], "email": mask_email(account["login"])} if account else None,
                "hasSave": has_save, "passwordConfigured": bool(account),
                "nextStep": "RESUME_GAME" if has_save else "CREATE_CHARACTER" if account else "LOGIN"}
