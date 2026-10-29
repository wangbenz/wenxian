"""一次护持的占用、结束、重放与数量事务检查；不调用模型，不作为玩法或叙事签收。"""

import pytest

from wenxian_runtime.domain import (
    Candidate,
    Change,
    DefinitionRef,
    GameState,
    Item,
    ItemEvent,
    ItemTransition,
    ItemUseEvent,
    ItemUseRule,
    RuntimeProblem,
    apply_candidate,
    item_use_constraints,
    opening,
)

REF = DefinitionRef(category="items", key="basic-protection-talisman")
RULE = ItemUseRule(id="simple-protection", definition=REF, kind="single-protection", name="贴身护光")
REFERENCES = [{"category": REF.category, "key": REF.key, "revision": 2, "content_hash": "d" * 64}]


def owned(quantity=1):
    state = opening()
    state.items.append(Item(id="talisman", name="领域检查成符", category="符箓", status="未用",
                            quantity=quantity, description="仅用于生命周期校验的合成物品", definition=REF))
    return state


def inventory(identifier, kind, before, after, status=None):
    return ItemEvent(id=identifier, kind=kind, source="明确的领域测试来源", reason="验证物品变化的结构与事务",
                     changes=[ItemTransition(item_id="talisman", before=before, after=after, status=status)])


def usage(identifier="activate-use", use_id="protection-1", kind="activate", link="activate-item", reason=None):
    return ItemUseEvent(id=identifier, use_id=use_id, item_id="talisman", rule_id=RULE.id, kind=kind,
                        item_event_id=link, end_reason=reason, reason="只验证使用状态迁移")


def commit(state, inventory_events=(), uses=(), turn=1, items=()):
    return apply_candidate(state, Candidate(scene_title="领域协议校验", narrative=["仅检查结构，不作为剧情证据。"],
                                            change=Change(reason="领域边界检查", items=list(items),
                                                          item_events=list(inventory_events), item_use_events=list(uses))),
                           REFERENCES, turn=turn, item_use_rules=[RULE],
                           adopted_definitions={"items:basic-protection-talisman"})


def activate(state=None):
    state = state or owned()
    quantity = state.items[-1].quantity
    return commit(state, [inventory("activate-item", "status", quantity, quantity, "已激活")], [usage()])


def test_acquisition_and_activation_in_one_turn_keep_origin_and_quantity():
    item = owned().items[-1].model_copy(update={"status": "已激活"})
    state = commit(opening(), [inventory("gift", "acquire", 0, 1, "未用"),
                               inventory("activate-item", "status", 1, 1, "已激活")], [usage()], items=[item])
    assert state.items[-1].quantity == 1
    assert state.item_origins["talisman"].event_id == "gift"
    assert state.item_uses["protection-1"].status == "active"


def test_no_use_event_keeps_active_effect_across_turn_and_serialization():
    state = activate()
    reread = GameState.model_validate_json(state.model_dump_json())
    unchanged = commit(reread, turn=2)
    assert unchanged.item_uses == state.item_uses and unchanged.items[-1].quantity == 1
    assert unchanged.item_uses["protection-1"].activated_turn == 1


@pytest.mark.parametrize("reason", ["effective_protection", "dismissed", "destroyed"])
def test_each_declared_end_consumes_one_and_retains_ended_record(reason):
    state = commit(activate(), [inventory("spent", "consume", 1, 0, "已用尽")],
                   [usage("end-use", kind="end", link="spent", reason=reason)], turn=2)
    use = state.item_uses["protection-1"]
    assert state.items[-1].quantity == 0 and use.status == "ended"
    assert (use.activated_turn, use.ended_turn, use.end_reason) == (1, 2, reason)
    assert GameState.model_validate_json(state.model_dump_json()).item_uses == state.item_uses


def test_ended_use_id_cannot_restart_but_another_unused_unit_can():
    state = commit(activate(owned(2)), [inventory("spent", "consume", 2, 1)],
                   [usage("end-use", kind="end", link="spent", reason="dismissed")], turn=2)
    before = state.model_dump_json()
    with pytest.raises(RuntimeProblem, match="未用物品"):
        commit(state, [inventory("activate-item", "status", 1, 1, "已激活")], [usage()], turn=3)
    assert state.model_dump_json() == before
    next_state = commit(state, [inventory("activate-item", "status", 1, 1, "已激活")],
                        [usage(use_id="protection-2")], turn=3)
    assert next_state.item_uses["protection-1"].status == "ended"
    assert next_state.item_uses["protection-2"].status == "active"


def test_one_physical_unit_cannot_support_two_active_uses():
    state = activate()
    with pytest.raises(RuntimeProblem) as failure:
        commit(state, [inventory("activate-item", "status", 1, 1, "已激活")], [usage(use_id="protection-2")], turn=2)
    assert failure.value.code == "ITEM_NOT_UNUSED" and len(state.item_uses) == 1


def test_end_without_matching_consumption_is_rejected_without_mutating_input():
    state = activate()
    before = state.model_dump_json()
    with pytest.raises(RuntimeProblem) as failure:
        commit(state, [inventory("hold", "status", 1, 1, "已结束")],
               [usage("end-use", kind="end", link="hold", reason="dismissed")], turn=2)
    assert failure.value.code == "ITEM_USE_END_CONFLICT" and state.model_dump_json() == before


def test_consuming_two_units_cannot_be_disguised_as_one_use_end():
    with pytest.raises(RuntimeProblem) as failure:
        commit(activate(owned(2)), [inventory("spent", "consume", 2, 0)],
               [usage("end-use", kind="end", link="spent", reason="effective_protection")], turn=2)
    assert failure.value.code == "ITEM_USE_END_CONFLICT"


def test_destroy_then_restock_cannot_hide_active_unit_loss_in_same_turn():
    with pytest.raises(RuntimeProblem) as failure:
        commit(activate(), [inventory("lost", "lose", 1, 0), inventory("replacement", "acquire", 0, 1)], turn=2)
    assert failure.value.code == "ITEM_USE_CONFLICT"


def test_unused_units_may_leave_without_ending_another_active_unit():
    state = commit(activate(owned(2)), [inventory("other-unit", "transfer", 2, 1)], turn=2)
    assert state.items[-1].quantity == 1 and state.item_uses["protection-1"].status == "active"


def test_lifecycle_event_order_must_match_inventory_event_order():
    with pytest.raises(RuntimeProblem) as failure:
        commit(owned(), [inventory("activate-item", "status", 1, 1, "已激活"),
                         inventory("spent", "consume", 1, 0)],
               [usage("end-use", kind="end", link="spent", reason="dismissed"), usage()])
    assert failure.value.code == "ITEM_USE_EVENT_REQUIRED"


def test_same_turn_activate_then_dismiss_consumes_exactly_one():
    state = commit(owned(), [inventory("activate-item", "status", 1, 1, "已激活"),
                            inventory("spent", "consume", 1, 0)],
                   [usage(), usage("end-use", kind="end", link="spent", reason="dismissed")])
    assert state.items[-1].quantity == 0 and state.item_uses["protection-1"].ended_turn == 1


@pytest.mark.parametrize("update", [{"rule_id": "not-adopted"}, {"item_id": "clothes"}])
def test_rule_cannot_apply_to_unknown_rule_or_another_item(update):
    with pytest.raises(RuntimeProblem) as failure:
        commit(owned(), [inventory("activate-item", "status", 1, 1, "已激活")], [usage().model_copy(update=update)])
    assert failure.value.code == "ITEM_USE_UNSUPPORTED"


def test_provided_definition_outside_world_adoption_is_not_accepted():
    with pytest.raises(RuntimeProblem) as failure:
        apply_candidate(opening(), Candidate(scene_title="引用边界", narrative=["仅检查采用清单。"]),
                        REFERENCES, turn=1, adopted_definitions=set())
    assert failure.value.code == "DEFINITION_NOT_ADOPTED"


@pytest.mark.parametrize("quantity", [1, 2])
def test_end_constraints_project_existing_rule_without_choosing_or_mutating(quantity):
    state = activate(owned(quantity))
    original = state.model_dump_json()
    assert item_use_constraints(state, [RULE]) == [
        {"use_id": "protection-1", "item_id": "talisman", "rule_id": RULE.id,
         "next_end_inventory_event": {"kind": "consume", "before": quantity, "after": quantity - 1},
         "allowed_end_reasons": ["effective_protection", "dismissed", "destroyed"]}
    ]
    assert state.model_dump_json() == original


def test_end_constraints_do_not_offer_restarting_ended_or_unconfigured_uses():
    assert item_use_constraints(opening(), []) == []
    ended = commit(activate(), [inventory("spent", "consume", 1, 0)],
                   [usage("end-use", kind="end", link="spent", reason="dismissed")], turn=2)
    assert item_use_constraints(ended, [RULE]) == []
    with pytest.raises(RuntimeProblem):
        item_use_constraints(activate(), [])
