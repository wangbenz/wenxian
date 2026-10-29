"""单份本地回放的只读审查材料；不调用模型、不迁移/修改数据库，不替人工判断剧情语义。"""

import json
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path


def item_changes(before, after):
    """直接比较已保存的物品投影；来历未知保持未知，不根据正文推算物品变化。"""
    old = {row["id"]: row for row in before.get("items", [])}
    new = {row["id"]: row for row in after.get("items", [])}
    return [
        {"item_id": key, "before": old.get(key), "after": new.get(key)}
        for key in sorted(old.keys() | new.keys())
        if old.get(key) != new.get(key)
    ]


def read_replay(database: Path, save_id: str | None = None):
    """在同一只读事务中读取指定命卷、已提交回合和调用审计；多卷必须明确选择，不读会话凭据。"""
    database = database.resolve(strict=True)
    with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA query_only=ON")
        connection.execute("BEGIN")
        if save_id is None:
            saves = connection.execute("SELECT id FROM runtime_save LIMIT 2").fetchall()
            if len(saves) != 1:
                raise ValueError("回放库必须恰有一份命卷，或用 --save-id 明确选择。")
            save_id = saves[0]["id"]
        saved = connection.execute(
            "SELECT revision,state_json FROM runtime_save WHERE id=?", (save_id,)
        ).fetchone()
        if saved is None:
            raise ValueError("没有找到指定命卷。")
        rows = connection.execute(
            "SELECT turn,request_id,action,candidate_json,state_json,references_json,mode,"
            "generation_audit_json FROM committed_turn WHERE save_id=? ORDER BY turn", (save_id,)
        ).fetchall()
        call_columns = {row[1] for row in connection.execute("PRAGMA table_info(model_call)")}
        shape_selection = "response_shape_json" if "response_shape_json" in call_columns else "'{}' AS response_shape_json"
        calls = connection.execute(
            "SELECT id,request_id,attempt_id,outcome,duration_ms,usage_json,error_type,"
            f"provider_status,validation_errors_json,{shape_selection} FROM model_call WHERE save_id=? ORDER BY id", (save_id,)
        ).fetchall()
        requests = connection.execute(
            "SELECT request_id,expected_revision,action,status,error_code FROM action_request "
            "WHERE save_id=? ORDER BY created_at,request_id", (save_id,)
        ).fetchall()
        global_call_count = connection.execute("SELECT COUNT(*) FROM model_call").fetchone()[0]

    turns, previous = [], {}
    for row in rows:
        state, candidate = json.loads(row["state_json"]), json.loads(row["candidate_json"])
        audit = json.loads(row["generation_audit_json"] or "{}")
        turns.append({
            "turn": row["turn"], "request_id": row["request_id"], "action": row["action"],
            "mode": row["mode"], "candidate": candidate, "state_after": state,
            "item_changes": item_changes(previous, state),
            "item_events": (candidate.get("change") or {}).get("item_events", []),
            "references": json.loads(row["references_json"]),
            "generation": {key: audit[key] for key in (
                "model", "prompt_hash", "protocol", "reasoning_effort", "tool_protocol", "inventory_protocol", "history_reads"
            ) if key in audit},
            "inventory_evidence": "events-1" if audit.get("inventory_protocol") == "events-1"
            else "opening" if row["turn"] == 0 else "legacy-no-event-protocol",
        })
        previous = state

    model_calls = []
    for row in calls:
        call = dict(row)
        call["usage"] = json.loads(call.pop("usage_json"))
        call["validation_errors"] = json.loads(call.pop("validation_errors_json"))
        call["response_shape"] = json.loads(call.pop("response_shape_json"))
        model_calls.append(call)
    return {
        "review_format": 1, "generated_at": datetime.now(timezone.utc).isoformat(),
        "save_id": save_id, "revision": saved["revision"],
        "engineering_checks": {
            "turns_contiguous_from_opening": [row["turn"] for row in rows] == list(range(saved["revision"] + 1)),
            "current_snapshot_matches_last_turn": bool(rows) and json.loads(saved["state_json"]) == previous,
        },
        "semantic_review": "pending-human-review",
        "limits": [
            "保存的正文与候选不是语义通过的证据；授权、来源真实性、前后矛盾须人工逐回核对。",
            "未保存的失败模型原始输出无法从本库还原；保留调用失败及字段诊断，不补造正文。",
            "旧回合未声明物品事件协议时保持旧记录，不补写来历或用新协议回填历史。",
        ],
        "turns": turns, "requests": [dict(row) for row in requests],
        "model_calls": model_calls, "model_calls_in_database": global_call_count,
    }
