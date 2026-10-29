"""本地启动边界的工程检查；不发模型请求，不替代真实玩家入口验收。"""

import base64
import importlib.util
import socket
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/run_local_player.py"
spec = importlib.util.spec_from_file_location("local_player", SCRIPT)
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


def test_master_key_is_private_and_survives_restart(tmp_path):
    path = tmp_path / "credential.key"
    local.ensure_master_key(path)
    original = path.read_bytes()
    assert len(base64.b64decode(original, validate=False)) == 32
    assert path.stat().st_mode & 0o777 == 0o600
    local.ensure_master_key(path)
    assert path.read_bytes() == original


@pytest.mark.parametrize("unsafe", ["corrupt", "public", "symlink"])
def test_invalid_existing_key_is_never_replaced(tmp_path, unsafe):
    path = tmp_path / "credential.key"
    value = base64.b64encode(b"x" * 32) + b"\n"
    if unsafe == "symlink":
        original = tmp_path / "original"
        original.write_bytes(value)
        original.chmod(0o600)
        path.symlink_to(original)
    else:
        path.write_bytes(b"invalid-key" if unsafe == "corrupt" else value)
        path.chmod(0o644 if unsafe == "public" else 0o600)
    before = path.read_bytes()
    with pytest.raises(RuntimeError, match="保留原文件"):
        local.ensure_master_key(path)
    assert path.read_bytes() == before


def test_real_local_environment_cannot_inherit_fixture_or_qa_world(tmp_path):
    original = {"PATH": "/test", "DEEPSEEK_API_KEY": "synthetic-key", "WENXIAN_MODEL_API_KEY": "other-key",
                "WENXIAN_GATEWAY": "fixture", "WENXIAN_MODEL_CALL_LIMIT": "12",
                "WENXIAN_RUNTIME_WORLD_PROFILE": "/qa-world", "WENXIAN_RUNTIME_DB": "/old-library"}
    backend, frontend = local.local_environments(tmp_path, 4189, 18094, "synthetic-key", original)
    assert backend["WENXIAN_GATEWAY"] == "langchain"
    assert backend["WENXIAN_MODEL_CALL_LIMIT"] == "unlimited"
    assert backend["WENXIAN_MODEL_TOOL_PROTOCOL"] == "deepseek-json"
    assert backend["WENXIAN_RUNTIME_PROFILE"] == "account"
    assert Path(backend["WENXIAN_RUNTIME_WORLD_PROFILE"]).name == "world-profile.json"
    assert Path(backend["WENXIAN_RUNTIME_DB"]) == tmp_path / "player.sqlite3"
    assert backend["WENXIAN_RUNTIME_ORIGINS"] == "http://127.0.0.1:4189,http://localhost:4189"
    assert "DEEPSEEK_API_KEY" not in frontend and "WENXIAN_MODEL_API_KEY" not in frontend
    assert frontend["VITE_RUNTIME_API_PROXY"] == "http://127.0.0.1:18094"
    assert original["WENXIAN_GATEWAY"] == "fixture"


def test_key_file_is_read_without_executing_shell(tmp_path):
    source = tmp_path / "local.env"
    marker = tmp_path / "should-not-exist"
    source.write_text(f"export DEEPSEEK_API_KEY='synthetic-$(touch {marker})-key' # local\n")
    assert local.read_platform_key(source, {}) == f"synthetic-$(touch {marker})-key"
    assert not marker.exists()
    assert local.read_platform_key(source, {"DEEPSEEK_API_KEY": "environment-key"}) == "environment-key"
    source.write_text("DEEPSEEK_API_KEY=${UNSET_VALUE}\n")
    with pytest.raises(RuntimeError):
        local.read_platform_key(source, {})


def test_busy_port_is_rejected_without_terminating_listener():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        port = listener.getsockname()[1]
        with pytest.raises(RuntimeError, match="不会终止其他进程"):
            local.require_free_port(port)
        assert listener.getsockname()[1] == port


def test_recently_closed_server_connection_does_not_block_restart():
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        port = listener.getsockname()[1]
        with socket.create_connection(("127.0.0.1", port)) as client:
            accepted, _ = listener.accept()
            accepted.close()
            assert client.recv(1) == b""
    local.require_free_port(port)
