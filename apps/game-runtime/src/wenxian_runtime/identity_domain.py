"""账号协议与密码/会话原语；不读取剧情，不将客户端角色声明作为权限。"""

import hashlib
import re
import secrets
from dataclasses import dataclass
from typing import Annotated

from pydantic import Field, StringConstraints, field_validator

from .domain import CharacterCreation, StrictModel

Password = Annotated[str, StringConstraints(min_length=8, max_length=72)]


class PlayerLogin(StrictModel):
    email: str = Field(min_length=3, max_length=255)
    password: str = Field(min_length=1, max_length=255, repr=False)

    @field_validator("email")
    @classmethod
    def email_address(cls, value):
        value = value.strip().lower()
        if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", value):
            raise ValueError("邮箱格式不正确")
        return value


class PlayerRegistration(PlayerLogin):
    password: Password = Field(repr=False)


class PlayerPassword(StrictModel):
    currentPassword: str = Field(min_length=1, max_length=255, repr=False)
    newPassword: Password = Field(repr=False)


class AccountCreation(StrictModel):
    request_id: Annotated[str, StringConstraints(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")]
    character: CharacterCreation


@dataclass(frozen=True)
class IdentityPolicy:
    """本地身份限制；默认延续 30 天绝对有效期、12 小时闲置失效。"""

    session_seconds: int = 30 * 86400
    idle_seconds: int = 12 * 3600
    failure_window: int = 600
    failures_per_subject: int = 5
    failures_per_client: int = 30
    registrations_per_client: int = 1
    maximum_players: int = 1000
    maximum_saves: int = 3
    secure_cookie: bool = False
    trial_enabled: bool = True
    allowed_origins: tuple[str, ...] = ()


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def password_hash(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=32768, r=8, p=1, maxmem=64*1024*1024)
    return f"scrypt:32768:8:1:{salt.hex()}:{digest.hex()}"


def password_matches(password: str, encoded: str) -> bool:
    try:
        algorithm, n, r, p, salt, digest = encoded.split(":")
        if (algorithm, n, r, p) != ("scrypt", "32768", "8", "1"):
            return False
        actual = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=int(n), r=int(r), p=int(p), maxmem=64*1024*1024)
        return secrets.compare_digest(actual, bytes.fromhex(digest))
    except (ValueError, TypeError):
        return False


def mask_email(value: str) -> str:
    name, domain = value.split("@", 1)
    return f"{name[:1]}***@{domain}"
