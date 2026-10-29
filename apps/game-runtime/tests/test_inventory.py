"""物品身份、事件数量、来历及事务边界；使用独立合成状态，不验证模型语义或付费调用。"""

import secrets

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from wenxian_runtime.api import create_app
from wenxian_runtime.domain import (
    Candidate,
    Change,
    DefinitionRef,
    GameState,
    Item,
    ItemEvent,
    ItemTransition,
    RuntimeProblem,
    apply_candidate,
    opening,
)


def note(quantity=1):
    return Item(
        id="note",
        name="掌柜的纸条",
        category="纸张",
        status="完好",
        quantity=quantity,
        description="青木坊掌柜亲笔写下的一张纸条。",
    )


def event(kind="acquire", item_id="note", before=0, after=1, event_id="note-received", status=None):
    return ItemEvent(
        id=event_id,
        kind=kind,
        source="青木坊掌柜交付",
        reason="本轮对话后实际交到手中",
        changes=[ItemTransition(item_id=item_id, before=before, after=after, status=status)],
    )


def candidate(items=(), events=()):
    return Candidate(
        scene_title="离线事件测试",
        narrative=["物品事件的合成测试正文。"],
        change=Change(reason="本轮明确变化", items=list(items), item_events=list(events)),
    )


@pytest.mark.parametrize(
    "field,value",
    [
        ("name", "引气丹"),
        ("category", "丹药"),
        ("description", "凭空增强后的用途"),
        ("definition", DefinitionRef(category="items", key="different-type")),
    ],
)
def test_existing_item_identity_cannot_be_overwritten(field, value):
    state = opening()
    changed = state.items[0].model_copy(update={field: value})
    references = [{"category": "items", "key": "different-type", "revision": 1, "content_hash": "a" * 64}]
    with pytest.raises(RuntimeProblem) as failure:
        apply_candidate(state, candidate([changed]), references, turn=1)
    assert failure.value.code == "ITEM_IDENTITY_CONFLICT"
    assert state.items[0].name == "随身衣物" and state.items[0].quantity == 1


@pytest.mark.parametrize("patch", [{"quantity": 2}, {"status": "凭空破损"}])
def test_item_quantity_or_status_requires_matching_event(patch):
    state = opening()
    with pytest.raises(RuntimeProblem) as failure:
        apply_candidate(state, candidate([state.items[0].model_copy(update=patch)]), [], turn=1)
    assert failure.value.code == "ITEM_EVENT_REQUIRED"
    with pytest.raises(RuntimeProblem) as failure:
        apply_candidate(state, candidate([note()]), [], turn=1)
    assert failure.value.code == "ITEM_ORIGIN_REQUIRED"


def test_new_story_item_and_sequential_consumption_keep_identity_origin_and_zero_record():
    original = opening()
    state = apply_candidate(
        original,
        candidate([note()], [event(after=2), event("consume", before=2, after=1, event_id="one-note-used")]),
        [],
        turn=1,
    )
    assert state.items[-1].quantity == 1 and len(original.items) == 1
    origin = state.item_origins["note"]
    assert origin.turn == 1 and origin.event_id == "note-received" and origin.source == "青木坊掌柜交付"
    consumed = apply_candidate(state, candidate(events=[event("consume", before=1, after=0)]), [], turn=2)
    assert consumed.items[-1].quantity == 0 and consumed.items[-1].name == "掌柜的纸条"
    assert consumed.item_origins["note"] == origin and state.items[-1].quantity == 1


def test_overconsumption_stale_before_and_duplicate_events_are_rejected():
    state = apply_candidate(opening(), candidate([note()], [event()]), [], turn=1)
    for events, code in [
        ([event("consume", before=2, after=0)], "ITEM_QUANTITY_CONFLICT"),
        (
            [event("consume", before=1, after=0), event("consume", before=1, after=0, event_id="again")],
            "ITEM_QUANTITY_CONFLICT",
        ),
        ([event("consume", before=1, after=0), event("consume", before=0, after=0)], "INVALID_ITEM_EVENT"),
        ([event(), event()], "INVALID_ITEM_EVENT"),
        ([event("consume", item_id="unknown", before=1, after=0)], "UNKNOWN_ITEM"),
    ]:
        with pytest.raises(RuntimeProblem) as failure:
            apply_candidate(state, candidate(events=events), [], turn=2)
        assert failure.value.code == code
        assert state.items[-1].quantity == 1
    with pytest.raises(ValidationError):
        event("consume", before=1, after=-1)


def test_status_update_and_explicit_transformation_preserve_predecessor():
    state = apply_candidate(
        opening(), candidate(events=[event("status", "clothes", 1, 1, status="沾湿")]), [], turn=1
    )
    assert state.items[0].status == "沾湿"
    strips = Item(
        id="cloth-strips",
        name="布条",
        category="随身物",
        status="完好",
        quantity=3,
        description="从随身衣物裁下的三条布料。",
    )
    transformation = ItemEvent(
        id="cut-cloth",
        kind="transform",
        source="主角裁开衣物",
        reason="本轮明确裁开",
        changes=[
            ItemTransition(item_id="clothes", before=1, after=0, status="已裁开"),
            ItemTransition(item_id="cloth-strips", before=0, after=3),
        ],
    )
    result = apply_candidate(state, candidate([strips], [transformation]), [], turn=2)
    assert result.items[0].name == "随身衣物" and result.items[0].quantity == 0
    assert result.items[-1].name == "布条" and result.items[-1].quantity == 3
    assert result.item_origins["cloth-strips"].predecessors == ["clothes"]
    assert result.item_origins["cloth-strips"].turn == 2
    with pytest.raises(RuntimeProblem) as failure:
        apply_candidate(state, candidate([strips], [event("transform", "cloth-strips", 0, 3)]), [], turn=2)
    assert failure.value.code == "INVALID_ITEM_EVENT"


def test_definition_category_and_actual_read_still_apply_to_item_events():
    item = note().model_copy(update={"definition": DefinitionRef(category="items", key="paper-note")})
    with pytest.raises(RuntimeProblem) as failure:
        apply_candidate(opening(), candidate([item], [event()]), [], turn=1)
    assert failure.value.code == "UNREAD_DEFINITION"
    refs = [{"category": "items", "key": "paper-note", "revision": 1, "content_hash": "a" * 64}]
    state = apply_candidate(opening(), candidate([item], [event()]), refs, turn=1)
    with pytest.raises(RuntimeProblem) as failure:
        apply_candidate(state, candidate(events=[event("consume", before=1, after=0)]), [], turn=2)
    assert failure.value.code == "UNREAD_DEFINITION"
    wrong_category = note().model_copy(
        update={"definition": DefinitionRef(category="arts", key="paper-note")}
    )
    with pytest.raises(RuntimeProblem) as failure:
        apply_candidate(
            opening(), candidate([wrong_category], [event()]), [{**refs[0], "category": "arts"}], turn=1
        )
    assert failure.value.code == "INVALID_ITEM_DEFINITION"


def test_legacy_state_and_no_inventory_change_remain_readable_without_invented_origins():
    legacy = opening().model_dump()
    legacy.pop("item_origins")
    state = GameState.model_validate(legacy)
    updated = apply_candidate(state, candidate(), [], turn=5)
    assert updated.items == state.items and updated.item_origins == {}


def test_inventory_events_commit_once_and_invalid_identity_rolls_back(tmp_path):
    import httpx
    from test_runtime import library_response
    class Gateway:
        mode = "fixture"

        def __init__(self):
            self.calls = 0
            self.result = candidate([note()], [event()])

        def generate(self, *args):
            self.calls += 1
            return self.result

    gateway = Gateway()
    app = create_app(tmp_path / "inventory.sqlite3", gateway,
                     library_transport=httpx.MockTransport(library_response))
    with TestClient(app) as client:
        client.headers["Authorization"] = f"Bearer {secrets.token_urlsafe(32)}"
        created = client.post(
            "/api/runtime/v1/sessions",
            json={"opening_id": "qingmu-trial", "name": "沈青", "goal": "核对来货",
                  "world_setting_hash": client.get("/api/runtime/v1/creation").json()["setting_hash"]},
        ).json()
        save_id = created["snapshot"]["save_id"]
        request = {"request_id": "item-receive-1", "expected_revision": 0, "action": "接过掌柜递来的纸条"}
        client.post("/api/runtime/v1/actions", json=request)
        client.post("/api/runtime/v1/actions", json=request)
        assert gateway.calls == 1
        saved = app.state.repo.save(save_id)
        assert saved["revision"] == 1 and saved["state"]["item_origins"]["note"]["turn"] == 1
        old_history = app.state.repo.history(save_id)
        assert old_history[-1]["result"]["change"]["item_events"][0]["source"] == "青木坊掌柜交付"
        gateway.result = candidate([note().model_copy(update={"name": "引气丹"})])
        request = {"request_id": "item-invalid-2", "expected_revision": 1, "action": "阅读纸条"}
        client.post("/api/runtime/v1/actions", json=request)
        assert app.state.repo.save(save_id) == saved and app.state.repo.history(save_id) == old_history
        assert (
            client.get("/api/runtime/v1/actions/item-invalid-2").json()["error"]["code"]
            == "ITEM_IDENTITY_CONFLICT"
        )
        gateway.result = candidate(events=[event("consume", before=1, after=0)])
        client.post("/api/runtime/v1/actions", json=request)
        client.post("/api/runtime/v1/actions", json=request)
        result = app.state.repo.save(save_id)
        assert result["revision"] == 2 and result["state"]["items"][-1]["quantity"] == 0
        assert app.state.repo.history(save_id, before=2) == old_history
        with app.state.repo.connection() as c:
            assert c.execute("SELECT COUNT(*) FROM model_call").fetchone()[0] == 0
