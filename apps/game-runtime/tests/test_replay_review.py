"""回放材料的只读、单卷隔离与证据边界；只使用临时 SQLite 和合成记录，不调用模型。"""

import hashlib
import json

import pytest

from wenxian_runtime.domain import (
    ActionInput,
    Candidate,
    Change,
    Item,
    ItemEvent,
    ItemTransition,
    apply_candidate,
    opening,
)
from wenxian_runtime.replay_review import read_replay
from wenxian_runtime.repository import Repository
from wenxian_runtime.service import ActionService


def setup_replay(tmp_path):
    path = tmp_path / "review.sqlite3"
    repo = Repository(path)
    repo.migrate()
    save_id = repo.create_session("synthetic-session-not-exported", opening(), "synthetic-fingerprint")
    return repo, path, save_id


def test_replay_packet_preserves_item_evidence_without_exporting_auth_or_writing(tmp_path):
    repo, path, save_id = setup_replay(tmp_path)
    action = ActionInput(request_id="receive-paper", expected_revision=0, action="接过实际交来的纸条")
    request, _ = repo.reserve(save_id, action)
    call_id = repo.spend_model_call(request, total_limit=3)
    repo.record_model_call(call_id, 12, usage={"input_tokens": 5, "output_tokens": 1, "total_tokens": 6})
    candidate = Candidate(
        scene_title="合成回放", narrative=["掌柜交来一张纸条。"],
        change=Change(
            reason="合成获得事件",
            items=[Item(id="paper", name="纸条", category="纸张", status="完好", quantity=1, description="空白纸条")],
            item_events=[ItemEvent(
                id="receive-paper", kind="acquire", source="掌柜交来", reason="本轮实际交付",
                changes=[ItemTransition(item_id="paper", before=0, after=1)],
            )],
        ),
    )
    state = apply_candidate(opening(), candidate, [], turn=1)
    repo.commit(request, candidate, state, [], "fixture", {
        "inventory_protocol": "events-1", "model": "offline-test", "private_api_key": "not-exported-key",
    })
    before = path.read_bytes()
    review = read_replay(path)
    assert path.read_bytes() == before
    assert review["engineering_checks"] == {
        "turns_contiguous_from_opening": True, "current_snapshot_matches_last_turn": True,
    }
    assert review["semantic_review"] == "pending-human-review"
    turn = review["turns"][1]
    assert turn["inventory_evidence"] == "events-1"
    assert turn["item_changes"][0]["before"] is None
    assert turn["item_changes"][0]["after"]["id"] == "paper"
    assert turn["item_events"][0]["source"] == "掌柜交来"
    assert turn["state_after"]["item_origins"]["paper"]["turn"] == 1
    assert review["model_calls_in_database"] == 1 and review["model_calls"][0]["usage"]["total_tokens"] == 6
    encoded = json.dumps(review)
    assert "token_hash" not in encoded and "not-exported-key" not in encoded
    assert hashlib.sha256(b"synthetic-session-not-exported").hexdigest() not in encoded


def test_legacy_and_failed_attempts_remain_visible_without_invented_history(tmp_path):
    repo, path, save_id = setup_replay(tmp_path)
    request, _ = repo.reserve(save_id, ActionInput(request_id="legacy-1", expected_revision=0, action="只问条件"))
    candidate = Candidate(scene_title="原回合", narrative=["掌柜说明了条件，尚未接受。"])
    repo.commit(request, candidate, apply_candidate(opening(), candidate, [], turn=1), [], "langchain")
    failed, _ = repo.reserve(save_id, ActionInput(request_id="failed-2", expected_revision=1, action="继续询问"))
    repo.fail(failed, "MODEL_BUDGET", "合成的额度耗尽")
    review = read_replay(path)
    assert review["turns"][1]["inventory_evidence"] == "legacy-no-event-protocol"
    assert review["turns"][1]["item_changes"] == review["turns"][1]["item_events"] == []
    assert review["turns"][1]["candidate"]["narrative"] == ["掌柜说明了条件，尚未接受。"]
    assert len(review["turns"]) == 2 and review["revision"] == 1
    assert review["requests"][1]["status"] == "FAILED" and review["model_calls_in_database"] == 0
    with repo.connection(write=True) as connection:
        connection.execute("UPDATE runtime_save SET revision=2 WHERE id=?", (save_id,))
    assert read_replay(path)["engineering_checks"]["turns_contiguous_from_opening"] is False


def test_multiple_saves_require_selection_and_other_save_data_are_not_exported(tmp_path):
    repo, path, save_id = setup_replay(tmp_path)
    other_state = opening().model_copy(update={"name": "other-save-private-name"})
    repo.create_session("another-synthetic-token", other_state, "another-fingerprint")
    with pytest.raises(ValueError, match="明确选择"):
        read_replay(path)
    with pytest.raises(ValueError, match="没有找到"):
        read_replay(path, "missing-save")
    review = read_replay(path, save_id)
    assert review["save_id"] == save_id
    assert "other-save-private-name" not in json.dumps(review)


def test_history_read_evidence_records_actual_owned_rows_without_extra_gateway_calls(tmp_path):
    repo, path, save_id = setup_replay(tmp_path)
    repo.create_session("another-token", opening().model_copy(update={"name": "other-private-name"}), "other")
    first, _ = repo.reserve(save_id, ActionInput(request_id="review-first", expected_revision=0, action="先问条件"))
    result = Candidate(scene_title="已保存的第一次询问", narrative=["对方说明条件，主角未答应。"])
    repo.commit(first, result, apply_candidate(opening(), result, [], turn=1), [], "fixture")

    class Gateway:
        mode = "fixture"
        calls = 0
        received = []

        def generate(self, state, action, library, history, spend, record):
            self.calls += 1
            self.received = [history(None, 3), history(1, 1)]
            return Candidate(scene_title="合成的回顾", narrative=["回顾已保存的条件。"])

        def audit_metadata(self):
            return {"history_reads": "not-actual-evidence", "inventory_protocol": "not-authoritative"}

    gateway = Gateway()
    request, _ = repo.reserve(save_id, ActionInput(request_id="review-second", expected_revision=1, action="回顾条件"))
    ActionService(repo, gateway, "http://unused.invalid").execute(request)
    review = read_replay(path, save_id)
    assert gateway.calls == 1 and repo.save(save_id)["revision"] == 2
    assert gateway.received[0] == repo.history(save_id, 2, 3)
    assert gateway.received[1] == repo.history(save_id, 1, 1)
    audit = review["turns"][2]["generation"]
    assert [row["turns"] for row in audit["history_reads"]] == [[0, 1], [0]]
    assert [(row["before_turn"], row["limit"]) for row in audit["history_reads"]] == [(None, 3), (1, 1)]
    assert audit["inventory_protocol"] == "events-1"
    assert review["model_calls_in_database"] == 0
    assert "other-private-name" not in json.dumps(review)
