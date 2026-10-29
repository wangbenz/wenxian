"""只读源库，在独立副本演练全部显式升级与恢复；逐表比对已有列，密文只输出散列。"""

import argparse
import hashlib
import json
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from wenxian_runtime.repository import Repository  # noqa: E402

def fingerprint(path, layout=None):
    with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as c:
        result = {}
        tables = {row[0] for row in c.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='schema_migration'")}
        for original in layout or sorted(tables):
            table = "identity_security_audit" if original == "runtime_admin_audit" and original not in tables else original
            quoted = '"' + table.replace('"', '""') + '"'
            columns = layout[original]["columns"] if layout else [row[1] for row in c.execute(f"PRAGMA table_info({quoted})")]
            selection = ",".join('"' + column.replace('"', '""') + '"' for column in columns)
            rows = c.execute(f"SELECT {selection} FROM {quoted}").fetchall()
            encoded = sorted(json.dumps(row, ensure_ascii=False, default=lambda value: {"blob_hex": value.hex()}) for row in rows)
            result[original] = {"columns": columns, "rows": len(rows), "sha256": hashlib.sha256(json.dumps(encoded, ensure_ascii=False).encode()).hexdigest()}
        return result


def backup(source, destination):
    if destination.exists():
        raise FileExistsError("不覆盖已有演练文件")
    destination.touch(mode=0o600)
    with sqlite3.connect(source.as_uri() + "?mode=ro", uri=True) as src, sqlite3.connect(destination) as dst:
        src.backup(dst)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    source, directory = args.source.resolve(), args.output_dir.resolve()
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    baseline, upgraded, restored = [directory / name for name in ("baseline.sqlite3", "upgraded.sqlite3", "restored.sqlite3")]
    before = fingerprint(source)
    backup(source, baseline)
    backup(baseline, upgraded)
    repo = Repository(upgraded)
    repo.migrate()
    repo.migrate()
    after = fingerprint(upgraded, before)
    backup(baseline, restored)
    with repo.connection() as c:
        migrations = [row[0] for row in c.execute("SELECT name FROM schema_migration ORDER BY name")]
        account_count = c.execute("SELECT COUNT(*) FROM identity_account").fetchone()[0]
        ownership_count = c.execute("SELECT COUNT(*) FROM account_save").fetchone()[0]
        foreign_keys = c.execute("PRAGMA foreign_key_check").fetchall()
        integrity = c.execute("PRAGMA integrity_check").fetchone()[0]
    result = {"source_unchanged": fingerprint(source) == before, "upgrade_preserved": after == before,
              "restore_preserved": fingerprint(restored) == before, "tables": after, "migrations": migrations,
              "accounts_total": account_count, "owners_total": ownership_count,
              "foreign_keys_ok": not foreign_keys, "integrity": integrity}
    (directory / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: value for key, value in result.items() if key != "tables"}, ensure_ascii=False))
    if not all(result[key] for key in ("source_unchanged", "upgrade_preserved", "restore_preserved", "foreign_keys_ok")) or integrity != "ok":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
