"""玩家凭证 AES-GCM 封装；主密钥仅从受限文件读取，错误不包含原文或密文。"""

import base64
import logging
import os
import stat
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .domain import RuntimeProblem


class CredentialCipher:
    """账号/服务/凭证编号绑定到密文，跨账号置换、篡改和错误主密钥均拒绝。"""

    def __init__(self, key: bytes | None):
        if key is not None and len(key) != 32:
            raise ValueError("凭证主密钥必须为 32 字节")
        self._cipher = AESGCM(key) if key else None

    @classmethod
    def configured(cls):
        filename = os.getenv("WENXIAN_RUNTIME_CREDENTIAL_KEY_FILE")
        if not filename:
            return cls(None)
        try:
            path = Path(filename)
            if path.is_symlink() or path.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO):
                raise ValueError()
            return cls(base64.b64decode(path.read_text().strip(), validate=True))
        except (OSError, ValueError):
            logging.getLogger(__name__).warning("凭证主密钥不可用，仅关闭凭证读写，不影响玩家登录。")
            return cls(None)

    def _require(self):
        if self._cipher is None:
            raise RuntimeProblem("CREDENTIAL_STORAGE_UNAVAILABLE", "密钥保存暂未开放，请稍后再试。", 503)
        return self._cipher

    @staticmethod
    def _aad(account_id, provider, credential_id):
        return f"wenxian-player-key:1:{account_id}:{provider}:{credential_id}".encode()

    def encrypt(self, value, account_id, provider, credential_id):
        nonce = os.urandom(12)
        return nonce + self._require().encrypt(nonce, value.encode(), self._aad(account_id, provider, credential_id))

    def decrypt(self, value, account_id, provider, credential_id):
        cipher = self._require()
        try:
            return cipher.decrypt(value[:12], value[12:], self._aad(account_id, provider, credential_id)).decode()
        except (InvalidTag, ValueError, TypeError, UnicodeError):
            raise RuntimeProblem("CREDENTIAL_UNREADABLE", "保存的密钥暂时无法读取，请重新保存后再发起新行动。", 409) from None
