"""导出本地独立回放的审查材料；仅允许 var 内已有数据库，输出不覆盖已有文件，不运行模型。"""

import argparse
import json
import os
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "src"))

from wenxian_runtime.replay_review import read_replay  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", required=True, type=Path, help="相对于本模块的 var/... 数据库")
    parser.add_argument("--save-id")
    parser.add_argument("--output", required=True, type=Path, help="var/... 下的新审查 JSON")
    args = parser.parse_args()
    database, output = (ROOT / args.db).resolve(), (ROOT / args.output).resolve()
    if not database.is_relative_to(ROOT / "var") or not output.is_relative_to(ROOT / "var"):
        parser.error("数据库与审查输出必须位于本模块 var 目录。")
    if not database.is_file():
        parser.error("回放数据库不存在；工具不会新建数据库。")
    if output.exists():
        parser.error("审查输出已经存在，请选择新文件名。")
    try:
        review = read_replay(database, args.save_id)
    except (ValueError, sqlite3.Error):
        parser.error("无法读取完整的本地回放结构，或需要用 --save-id 明确选择命卷；不会自动迁移。")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x") as target:
        os.fchmod(target.fileno(), 0o600)
        json.dump(review, target, ensure_ascii=False, indent=2)
    print(f"审查材料已写入 {output}；语义结论待人工复核，未调用模型。")


if __name__ == "__main__":
    main()
