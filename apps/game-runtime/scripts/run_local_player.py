"""启动本机日常玩家入口；仅使用独立持久库与真实模型，失败时停止自己创建的服务。"""

import argparse
import base64
import json
import os
import re
import shlex
import shutil
import signal
import socket
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
H5 = ROOT.parent / "player-h5"


def read_platform_key(source: Path, environ):
    """只读取指定服务端凭证，不执行环境文件；错误与日志不含原文。"""
    key = environ.get("WENXIAN_MODEL_API_KEY") or environ.get("DEEPSEEK_API_KEY")
    if key and not key.startswith("${"):
        return key
    try:
        for line in source.read_text().splitlines():
            match = re.match(r"(?:export\s+)?DEEPSEEK_API_KEY=(.*)", line.strip())
            if match:
                parts = shlex.split(match[1], comments=True)
                if len(parts) == 1 and parts[0] and not parts[0].startswith("${"):
                    return parts[0]
    except (OSError, ValueError):
        pass
    raise RuntimeError("没有可用的服务端 DeepSeek 凭证，请检查指定的本地环境文件。")


def ensure_master_key(path: Path):
    """主密钥只创建一次；已有文件必须安全且有效，禁止覆盖或重建。"""
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        pass
    else:
        with os.fdopen(fd, "w") as target:
            target.write(base64.b64encode(os.urandom(32)).decode() + "\n")
    try:
        mode = path.lstat().st_mode
        if not stat.S_ISREG(mode) or mode & 0o077 or len(base64.b64decode(path.read_text().strip(), validate=True)) != 32:
            raise ValueError()
    except (OSError, ValueError):
        raise RuntimeError("现有凭证主文件不可用；已停止启动并保留原文件，请检查备份与权限。") from None


def local_environments(data_dir: Path, port: int, api_port: int, key: str, environ):
    """固定本地账号与真实模型配置；前端子进程不继承平台密钥。"""
    backend = dict(environ)
    backend.update(
        PYTHONPATH=str(ROOT / "src"),
        WENXIAN_RUNTIME_PROFILE="account",
        WENXIAN_RUNTIME_DB=str(data_dir / "player.sqlite3"),
        WENXIAN_RUNTIME_CREDENTIAL_KEY_FILE=str(data_dir / "credential.key"),
        WENXIAN_RUNTIME_GENERATION_CONFIG=str(ROOT / "resources/generation.local.json"),
        WENXIAN_RUNTIME_WORLD_PROFILE=str(ROOT / "resources/world-profile.json"),
        WENXIAN_RUNTIME_ORIGINS=f"http://127.0.0.1:{port},http://localhost:{port}",
        WENXIAN_RUNTIME_SECURE_COOKIE="false",
        WENXIAN_GATEWAY="langchain",
        WENXIAN_MODEL="deepseek-flash",
        WENXIAN_MODEL_API_KEY=key,
        WENXIAN_MODEL_BASE_URL="https://api.deepseek.com",
        WENXIAN_MODEL_REASONING_EFFORT="none",
        WENXIAN_MODEL_TOOL_PROTOCOL="deepseek-json",
        WENXIAN_MODEL_CALL_LIMIT="unlimited",
        LANGSMITH_TRACING="false", LANGCHAIN_TRACING_V2="false",
    )
    frontend = dict(environ)
    for name in ("WENXIAN_MODEL_API_KEY", "DEEPSEEK_API_KEY"):
        frontend.pop(name, None)
    frontend["VITE_RUNTIME_API_PROXY"] = f"http://127.0.0.1:{api_port}"
    return backend, frontend


def require_free_port(port: int):
    with socket.socket() as probe:
        # 与实际服务一致，允许复用退出后尚处 TIME_WAIT 的连接；仍拒绝正在监听的进程。
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", port))
        except OSError:
            raise RuntimeError(f"本地端口 {port} 已被占用；请先停止对应旧服务，启动器不会终止其他进程。") from None


def read_json(url: str):
    with urllib.request.urlopen(url, timeout=2) as response:
        return json.load(response)


def wait_ready(process, url: str):
    deadline = time.monotonic() + 30
    while process.poll() is None and time.monotonic() < deadline:
        try:
            health = read_json(url)
            if health.get("mode") == "langchain" and health.get("profile") == "account":
                return
        except (OSError, ValueError):
            pass
        time.sleep(0.2)
    raise RuntimeError("本地玩家服务没有就绪，请检查前面的启动日志；没有切回旧后端。")


def stop_child(process):
    """只停止本启动器创建的进程组，正常退出后持久库与主文件保留。"""
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()


def main():
    def interrupted(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=4189)
    parser.add_argument("--api-port", type=int, default=18094)
    parser.add_argument("--host", choices=["127.0.0.1"], default="127.0.0.1")
    parser.add_argument("--strictPort", action="store_true", help="兼容 Vite 参数；启动始终使用固定端口")
    parser.add_argument("--data-dir", type=Path, default=ROOT / "var/local-player")
    parser.add_argument("--credentials-env-file", type=Path, default=ROOT.parent / "game-api/.env.local")
    args = parser.parse_args()
    children = []
    try:
        if not all(1024 <= value <= 65535 for value in (args.port, args.api_port)) or args.port == args.api_port:
            raise RuntimeError("前后端需要两个不同的本地端口（1024–65535）。")
        data_dir = args.data_dir.resolve()
        if not data_dir.is_relative_to(ROOT / "var"):
            raise RuntimeError("本地玩家数据必须放在 game-runtime/var 内，不得使用其他系统的数据目录。")
        require_free_port(args.port)
        require_free_port(args.api_port)
        npm = shutil.which("npm")
        if not npm:
            raise RuntimeError("没有找到前端运行环境，请先启用项目的 Node/npm。")
        key = read_platform_key(args.credentials_env_file, os.environ)
        library = os.environ.get("WENXIAN_LIBRARY_URL", "http://127.0.0.1:8011").rstrip("/")
        try:
            read_json(f"{library}/api/library/v1/realms/lianqi/")
        except (OSError, ValueError):
            raise RuntimeError("本地资料库尚未就绪，请先启动资料库；不会用模拟设定替代。") from None
        data_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
        ensure_master_key(data_dir / "credential.key")
        backend, frontend = local_environments(data_dir, args.port, args.api_port, key, os.environ)
        api = subprocess.Popen([sys.executable, "-m", "uvicorn", "wenxian_runtime.api:app", "--host", "127.0.0.1",
                                "--port", str(args.api_port)], cwd=ROOT, env=backend, start_new_session=True)
        children.append(api)
        wait_ready(api, f"http://127.0.0.1:{args.api_port}/api/runtime/v1/health")
        ui = subprocess.Popen([npm, "run", "dev:ui", "--", "--config", "vite.local.config.ts", "--host", "127.0.0.1",
                               "--port", str(args.port), "--strictPort"], cwd=H5, env=frontend, start_new_session=True)
        children.append(ui)
        wait_ready(ui, f"http://127.0.0.1:{args.port}/api/runtime/v1/health")
        print(f"\n本地玩家入口已就绪：http://127.0.0.1:{args.port}/（真实续写，Ctrl-C 停止，数据保留）\n", flush=True)
        while all(process.poll() is None for process in children):
            time.sleep(0.5)
        raise RuntimeError("本地子服务已退出，请检查启动日志后重新启动；已有命卷保留。")
    except KeyboardInterrupt:
        return 0
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    finally:
        for process in reversed(children):
            stop_child(process)


if __name__ == "__main__":
    sys.exit(main())
