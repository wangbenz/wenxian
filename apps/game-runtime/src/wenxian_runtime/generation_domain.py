"""玩家推演协议与只读运行配置；平台内部模型/密钥不属于玩家展示合同。"""

import hashlib
import logging
import os
from pathlib import Path
from typing import Annotated, Literal
from urllib.parse import urlparse

from pydantic import Field, SecretStr, StringConstraints, model_validator

from .domain import StrictModel

Code = Annotated[str, StringConstraints(min_length=1, max_length=64, pattern=r"^[A-Za-z0-9_.-]+$")]
TierCode = Literal["LIGHT", "BALANCED", "DEEP"]


class ModelOption(StrictModel):
    code: Code
    name: str = Field(min_length=1, max_length=80)
    api_model: str = Field(min_length=1, max_length=200)
    reasoning_effort: str | None = None
    tool_protocol: Literal["standard", "deepseek-strict", "deepseek-json"] = "standard"


class ProviderOption(StrictModel):
    code: Code
    name: str = Field(min_length=1, max_length=80)
    base_url: str
    personal_enabled: bool = False
    platform_key_env: Annotated[str, StringConstraints(pattern=r"^WENXIAN_[A-Z0-9_]+$")] = "WENXIAN_MODEL_API_KEY"
    models: list[ModelOption] = Field(min_length=1, max_length=20)

    @model_validator(mode="after")
    def endpoint_and_models(self):
        url = urlparse(self.base_url)
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError("模型地址必须是服务器预设的 HTTPS 地址，不能含认证信息")
        if len({item.code for item in self.models}) != len(self.models):
            raise ValueError("模型编号不能重复")
        return self


class PlatformTier(StrictModel):
    code: TierCode
    name: str = Field(min_length=1, max_length=40)
    description: str = Field(default="", max_length=240)
    point_cost: int = Field(ge=1, le=10000)
    provider: Code
    model: Code


class GenerationConfig(StrictModel):
    """只读配置在进程启动时冻结；无配置不开放付费模型，也不推断收费规则。"""

    unavailable: bool = Field(default=False, exclude=True)
    opening_grant: int = Field(default=0, ge=0, le=1000000)
    grant_valid_days: int | None = Field(default=None, ge=1, le=3650)
    providers: list[ProviderOption] = Field(default_factory=list, max_length=20)
    tiers: list[PlatformTier] = Field(default_factory=list, max_length=3)
    default_tier: TierCode | None = None

    @model_validator(mode="after")
    def references(self):
        if len({p.code for p in self.providers}) != len(self.providers) or len({t.code for t in self.tiers}) != len(self.tiers):
            raise ValueError("配置编号重复")
        for tier in self.tiers:
            provider = next((p for p in self.providers if p.code == tier.provider), None)
            if not provider or not any(m.code == tier.model for m in provider.models):
                raise ValueError("档位未关联有效的服务端模型")
        if self.tiers and self.default_tier not in {t.code for t in self.tiers}:
            raise ValueError("需要配置默认档位")
        return self

    def digest(self):
        return hashlib.sha256(self.model_dump_json().encode()).hexdigest()


class GenerationPreference(StrictModel):
    mode: Literal["PLATFORM", "PERSONAL"]
    provider: Code | None = None
    model: Code | None = None
    tierCode: TierCode | None = None


class CredentialInput(StrictModel):
    model: Code
    apiKey: SecretStr = Field(min_length=20, max_length=4096)


def read_generation_config():
    source = os.getenv("WENXIAN_RUNTIME_GENERATION_CONFIG")
    if not source:
        return GenerationConfig()
    try:
        return GenerationConfig.model_validate_json(Path(source).read_text())
    except (ValueError, OSError):
        # 配置可能误放密钥，不能把 Pydantic input 或原文件内容写到日志。
        logging.getLogger(__name__).warning("玩家推演配置不可用，关闭可选模型目录；账号和已有命卷读取继续可用。")
        return GenerationConfig(unavailable=True)
