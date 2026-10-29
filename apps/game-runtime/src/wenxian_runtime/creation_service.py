"""创角推荐应用服务：冻结草稿与适用设定，调度一次类型化生成；候选不扣剧情点数或写入游戏事实。"""

import hashlib
import json
import os
import time

from .creation_domain import NameSuggestions, PathSuggestions, SuggestionInput
from .creation_gateway import CreationGateway
from .creation_repository import CreationRepository
from .domain import RuntimeProblem
from .repository import dump


class CreationService:
    """只允许已登录玩家、预设平台模型及已确认场景；失败留原请求，不以静态候选假装 AI 生成。"""

    def __init__(self, repository, worlds, generation, call_limit, gateway=None):
        self.repo, self.worlds, self.generation = CreationRepository(repository), worlds, generation
        self.call_limit, self.gateway = call_limit, gateway or CreationGateway()

    def reserve(self, account_id, body):
        fingerprint = hashlib.sha256(dump(body.model_dump(exclude={"request_id"})).encode()).hexdigest()
        existing = self.repo.find(account_id, body.request_id, fingerprint)
        if existing:
            return existing, False
        route = self.generation.creation_route()
        context = {}
        if body.kind == "paths":
            setting = self.worlds.current()
            if setting.digest() != body.world_setting_hash:
                raise RuntimeProblem("SETTING_CHANGED", "开篇设定已有更新，请重新读取，填写内容已保留。", 409)
            context = {"setting_hash": setting.digest(), "opening": setting.opening_state().model_dump(mode="json"),
                       "summary": setting.profile.summary}
        return self.repo.reserve(account_id, body, fingerprint, context, route)

    def execute(self, account_id, request_id):
        started = time.monotonic()
        try:
            if not self.repo.start_call(account_id, request_id, self.call_limit):
                return
            row = self.repo.find(account_id, request_id)
            body = SuggestionInput.model_validate_json(row["input_json"])
            route = json.loads(row["route_json"])
            key = os.getenv(route.pop("key_env"), "")
            if not key:
                raise RuntimeProblem("MODEL_NOT_CONFIGURED", "生成服务暂不可用。", 503)
            result, usage = self.gateway.generate(body, json.loads(row["context_json"]), route, key)
            result_type = NameSuggestions if body.kind == "names" else PathSuggestions
            validated = result_type.model_validate(result.model_dump())
            self.repo.finish(account_id, request_id, result=validated.model_dump(), usage=usage,
                             duration_ms=int((time.monotonic() - started) * 1000))
        except Exception as exc:
            # 固定错误和异常类足够诊断；不将供应商异常正文、提示词或密钥带回页面/日志。
            self.repo.finish(account_id, request_id, error_code=exc.code if isinstance(exc, RuntimeProblem) else "SUGGESTION_FAILED",
                             error_type=type(exc).__name__, duration_ms=int((time.monotonic() - started) * 1000))

    def status(self, account_id, request_id):
        row = self.repo.find(account_id, request_id)
        if not row:
            raise RuntimeProblem("SUGGESTION_NOT_FOUND", "没有找到这批候选，请返回原草稿重试。", 404)
        return self.repo.view(row)
