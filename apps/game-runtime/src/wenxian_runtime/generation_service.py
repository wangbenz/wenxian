"""玩家推演应用服务：读取服务器目录、保存加密密钥、冻结路由与点数，不主动发模型请求。"""

import json
import os
import time
import uuid
from datetime import datetime, timezone

from .credit_repository import CreditRepository
from .domain import RuntimeProblem
from .gateway import LangChainGateway
from .generation_repository import GenerationRepository


class GenerationService:
    """所有执行合同只含路由和凭证编号；明文密钥只在构造当次网关时解密。"""

    def __init__(self, repository, config, cipher, default_gateway, clock=None, gateway_factory=None):
        self.repo = GenerationRepository(repository)
        self.clock = clock or time.time
        self.credits = CreditRepository(repository, self.clock)
        self.config, self.cipher, self.default_gateway = config, cipher, default_gateway
        self.gateway_factory = gateway_factory or self._gateway

    def _provider_model(self, provider_code, model_code, personal=False):
        self._available()
        provider = next((row for row in self.config.providers if row.code == provider_code), None)
        model = next((row for row in provider.models if row.code == model_code), None) if provider else None
        if not provider or not model or (personal and not provider.personal_enabled):
            raise RuntimeProblem("MODEL_UNAVAILABLE", "该续写服务当前不可用，请重新选择。", 409)
        return provider, model

    def _tier(self, code):
        self._available()
        tier = next((row for row in self.config.tiers if row.code == (code or self.config.default_tier)), None)
        if not tier:
            raise RuntimeProblem("MODEL_NOT_CONFIGURED", "平台续写尚未配置，原行动已保留。", 503)
        return tier

    def settings(self, account_id):
        self._available()
        preference = self.repo.preference(account_id) or {"mode": "PLATFORM", "tier_code": self.config.default_tier}
        credentials = self.repo.credentials(account_id)
        balance = self.credits.balance(account_id)
        platform_ready = self.default_gateway.mode == "langchain" and any(
            os.getenv(provider.platform_key_env)
            for provider in self.config.providers
            if any(tier.provider == provider.code for tier in self.config.tiers)
        )
        return {
            "mode": preference["mode"],
            "platform": {"turnLimit": balance["granted"], "usedTurns": balance["spent"],
                         "remainingTurns": balance["available"], "available": platform_ready},
            "platformTiers": [{"code": tier.code, "name": tier.name, "description": tier.description,
                              "pointCost": tier.point_cost, "selected": tier.code == preference.get("tier_code", self.config.default_tier)} for tier in self.config.tiers],
            "selectedPlatformTier": preference.get("tier_code") or self.config.default_tier,
            "personalProviders": [{"code": provider.code, "name": provider.name,
                                   "credentialConfigured": provider.code in credentials,
                                   "credentialStatus": ("ACTIVE" if credentials[provider.code]["verified_at"] else "SAVED") if provider.code in credentials else None,
                                   "maskedSuffix": None,
                                   "lastVerifiedAt": datetime.fromtimestamp(credentials[provider.code]["verified_at"], timezone.utc).isoformat() if credentials.get(provider.code, {}).get("verified_at") else None,
                                   "models": [{"code": model.code, "name": model.name} for model in provider.models]}
                                  for provider in self.config.providers if provider.personal_enabled],
            "selectedPersonalProvider": preference.get("provider_code"), "selectedPersonalModel": preference.get("model_code"),
        }

    def save_settings(self, account_id, body):
        if body.mode == "PLATFORM":
            tier = self._tier(body.tierCode)
            body = body.model_copy(update={"tierCode": tier.code, "provider": None, "model": None})
        else:
            self._provider_model(body.provider, body.model, True)
        self.repo.set_preference(account_id, body, int(self.clock()))
        return self.settings(account_id)

    def save_credential(self, account_id, provider, body):
        self._provider_model(provider, body.model, True)
        key = body.apiKey.get_secret_value()
        if any(character.isspace() for character in key):
            raise RuntimeProblem("INVALID_CREDENTIAL", "密钥不能含空白字符。", 400)
        identifier = uuid.uuid4().hex
        encrypted = self.cipher.encrypt(key, account_id, provider, identifier)
        self.repo.save_credential(account_id, provider, identifier, encrypted, body.model, int(self.clock()))
        return self.settings(account_id)

    def delete_credential(self, account_id, provider):
        self.repo.delete_credential(account_id, provider)
        return self.settings(account_id)

    def opening_grant(self, c, account_id):
        self._available()
        expiry = int(self.clock()) + self.config.grant_valid_days*86400 if self.config.grant_valid_days else None
        self.credits.grant(c, account_id, "first-opening", self.config.opening_grant, expiry)

    def creation_route(self):
        """创角前使用预设平台模型生成免费草稿候选；不预占剧情点数，也不触碰个人凭证。"""
        if self.default_gateway.mode != "langchain":
            raise RuntimeProblem("MODEL_NOT_CONFIGURED", "人物候选生成暂未开放，可以直接填写。", 503)
        tier = self._tier(self.config.default_tier)
        provider, model = self._provider_model(tier.provider, tier.model)
        if not os.getenv(provider.platform_key_env):
            raise RuntimeProblem("MODEL_NOT_CONFIGURED", "人物候选生成暂不可用，可以直接填写。", 503)
        return {"model": model.api_model, "base_url": provider.base_url, "reasoning_effort": model.reasoning_effort,
                "tool_protocol": model.tool_protocol, "key_env": provider.platform_key_env,
                "config_hash": self.config.digest()}

    def _available(self):
        if self.config.unavailable:
            raise RuntimeProblem("GENERATION_CONFIG_UNAVAILABLE", "续写配置暂时无法读取，已有命卷记录仍可查看。", 503)

    def admit(self, c, request, account_id):
        contract = self.repo.contract(c, request)
        if not contract:
            self._available()
            preference = self.repo.preference(account_id, c) or {"mode": "PLATFORM", "tier_code": self.config.default_tier}
            credential_id, points = None, 0
            if self.default_gateway.mode == "fixture":
                mode, route = "FIXTURE", {"config_hash": self.config.digest()}
            elif self.default_gateway.mode == "disabled":
                raise RuntimeProblem("MODEL_NOT_CONFIGURED", "本地服务尚未启用续写，原行动已保留。", 503)
            else:
                mode = preference["mode"]
                if mode == "PLATFORM":
                    tier = self._tier(preference.get("tier_code"))
                    provider, model = self._provider_model(tier.provider, tier.model)
                    if not os.getenv(provider.platform_key_env):
                        raise RuntimeProblem("MODEL_NOT_CONFIGURED", "平台续写暂不可用，原行动已保留。", 503)
                    points = tier.point_cost
                else:
                    provider, model = self._provider_model(preference.get("provider_code"), preference.get("model_code"), True)
                    credential_id = self.repo.active_credential(c, account_id, provider.code)
                route = {"provider": provider.code, "model": model.api_model, "base_url": provider.base_url,
                         "reasoning_effort": model.reasoning_effort, "tool_protocol": model.tool_protocol,
                         "key_env": provider.platform_key_env if mode == "PLATFORM" else None,
                         "config_hash": self.config.digest()}
            self.repo.insert_contract(c, request, account_id, mode, points, route, credential_id, int(self.clock()))
            contract = self.repo.contract(c, request)
        if contract["account_id"] != account_id:
            raise RuntimeProblem("NOT_FOUND", "没有找到原请求。", 404)
        if contract["credential_id"] and not c.execute("SELECT 1 FROM user_model_credential WHERE id=? AND account_id=? AND active=1 AND ciphertext IS NOT NULL",
                                                     (contract["credential_id"], account_id)).fetchone():
            raise RuntimeProblem("CREDENTIAL_CHANGED", "原行动使用的密钥已变化，请重新发起新行动。", 409)
        self.credits.reserve(c, request, account_id, contract["points"])

    def gateway_for(self, request, fallback=None):
        contract = self.repo.execution(request)
        if not contract:
            return fallback or self.default_gateway  # 旧独立试玩不套用玩家额度或账号模型设置。
        if contract["mode"] == "FIXTURE":
            if self.default_gateway.mode != "fixture":
                raise RuntimeProblem("EXECUTION_MODE_CHANGED", "原行动属于模拟联调，不能改为真实续写。", 409)
            return self.default_gateway
        if self.default_gateway.mode != "langchain":
            raise RuntimeProblem("EXECUTION_MODE_CHANGED", "原行动的续写方式暂不可用，请保留原请求。", 409)
        route = json.loads(contract["route_json"])
        if contract["mode"] == "PERSONAL":
            credential = self.repo.credential(contract["account_id"], contract["credential_id"])
            key = self.cipher.decrypt(credential["ciphertext"], contract["account_id"], credential["provider_code"], credential["id"])
        else:
            key = os.getenv(route["key_env"], "")
        if not key:
            raise RuntimeProblem("MODEL_NOT_CONFIGURED", "续写服务暂不可用，原行动已保留。", 503)
        return self.gateway_factory(route, key)

    @staticmethod
    def _gateway(route, key):
        return LangChainGateway(model=route["model"], key=key, base_url=route["base_url"], reasoning_effort=route.get("reasoning_effort"),
                                tool_protocol=route.get("tool_protocol", "standard"))

    @staticmethod
    def assert_credential_active(c, request):
        contract = GenerationRepository.contract(c, request)
        if contract and contract["credential_id"] and not c.execute(
            "SELECT 1 FROM user_model_credential WHERE id=? AND account_id=? AND active=1 AND ciphertext IS NOT NULL",
            (contract["credential_id"], contract["account_id"]),
        ).fetchone():
            raise RuntimeProblem("CREDENTIAL_CHANGED", "原行动的密钥已变化，后续请求已停止。", 409)

    def finish(self, c, request, success):
        self.credits.finish(c, request, success)
        if success:
            c.execute("UPDATE user_model_credential SET verified_at=? WHERE active=1 AND id IN "
                      "(SELECT credential_id FROM execution_contract WHERE save_id=? AND request_id=? AND mode='PERSONAL')",
                      (int(self.clock()), request["save_id"], request["request_id"]))
