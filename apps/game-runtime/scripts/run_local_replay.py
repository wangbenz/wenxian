"""显式配置本地真实回放；仅从指定文件取指定凭证，不拷贝或输出密钥，复用持久化预算。"""

import argparse
import os
import re
import shlex
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--model", required=True)
parser.add_argument("--call-limit", required=True, help="0 仅准备，正整数限制整批次数，unlimited 显式不设总次数上限")
parser.add_argument("--db", required=True)
parser.add_argument("--base-url", default="https://api.deepseek.com")
parser.add_argument("--credentials-env-file", type=Path)
parser.add_argument("--credential-name", default="WENXIAN_MODEL_API_KEY")
parser.add_argument("--reasoning-effort")
parser.add_argument("--tool-protocol", choices=["standard", "deepseek-strict", "deepseek-json"], default="standard")
parser.add_argument("--port", type=int, default=18091)
args = parser.parse_args()
if args.call_limit != "unlimited" and not args.call_limit.isdecimal():
    parser.error("调用上限必须为零（仅准备）、正整数或已明确采用的 unlimited")
database = (ROOT / args.db).resolve()
if not database.is_relative_to(ROOT / "var"):
    parser.error("回放数据库必须放在本模块 var 目录，不能使用其他系统的数据库")
key = os.getenv(args.credential_name, "")
if args.credentials_env_file:
    for line in args.credentials_env_file.read_text().splitlines():
        match = re.match(r"(?:export\s+)?([A-Z0-9_]+)=(.*)", line.strip())
        if match and match[1] == args.credential_name:
            parts = shlex.split(match[2], comments=True)
            key = parts[0] if len(parts) == 1 else ""
if not key or key.startswith("${"):
    parser.error("指定来源中没有可用的服务端凭证")
os.environ.update(
    WENXIAN_GATEWAY="langchain",
    WENXIAN_MODEL=args.model,
    WENXIAN_MODEL_API_KEY=key,
    WENXIAN_MODEL_BASE_URL=args.base_url,
    WENXIAN_MODEL_CALL_LIMIT=str(args.call_limit),
    WENXIAN_MODEL_TOOL_PROTOCOL=args.tool_protocol,
    WENXIAN_RUNTIME_DB=str(database),
    LANGSMITH_TRACING="false",
    LANGCHAIN_TRACING_V2="false",
)
if args.reasoning_effort:
    os.environ["WENXIAN_MODEL_REASONING_EFFORT"] = args.reasoning_effort
sys.path.insert(0, str(ROOT / "src"))
import uvicorn  # noqa: E402

uvicorn.run("wenxian_runtime.api:app", host="127.0.0.1", port=args.port)
