"""本地创角应用服务；显式场景前提与玩家资料经确认后一次保存，不调用模型。"""

import hashlib
import re

from .domain import CharacterCreation, RuntimeProblem
from .repository import dump


class SessionService:
    """本机每卷独立能力凭据；重试资料不一致时拒绝，不能覆盖已有角色或历史。"""

    def __init__(self, repository, worlds):
        self.repo, self.worlds = repository, worlds

    def preview(self):
        return self.worlds.preview()

    def create(self, character: CharacterCreation, token: str) -> str:
        # 客户端首次确认前生成并保存高熵随机凭据，丢失响应后沿用它找回同一卷。
        if not re.fullmatch(r"[A-Za-z0-9_-]{43,128}", token):
            raise RuntimeProblem("INVALID_SESSION", "开卷凭据无效，请重新进入创角页面。", 400)
        fingerprint = hashlib.sha256(dump(character.model_dump(mode="json", exclude_none=True)).encode()).hexdigest()
        old = self.repo.session_creation(token, fingerprint)
        if old:
            return old
        state, setting = self.worlds.prepare(character)
        return self.repo.create_session(token, state, fingerprint, setting)
