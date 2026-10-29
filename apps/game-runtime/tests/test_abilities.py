"""本领身份、资料类别与进展的领域检查；合成候选不作为真实学习或叙事验收。"""

import pytest

from wenxian_runtime.domain import (
    Ability,
    Candidate,
    Change,
    DefinitionRef,
    RuntimeProblem,
    apply_candidate,
    opening,
)

REFERENCES = [{"category": "arts", "key": key, "revision": 2, "content_hash": "a" * 64}
              for key in ("basic-breathing", "object-control")]


def propose(state, abilities):
    return apply_candidate(state, Candidate(scene_title="领域校验", narrative=["仅校验本领归并。"],
                           change=Change(reason="领域边界检查", abilities=abilities)), REFERENCES, turn=1)


@pytest.mark.parametrize("field,value", [
    ("name", "另一种本领"),
    ("kind", "SPELL"),
    ("source", "替换已确认的首次来历"),
    ("description", "替换已确认的用途与边界"),
    ("definition", DefinitionRef(category="arts", key="object-control")),
    ("definition", None),
])
def test_existing_ability_identity_cannot_be_replaced(field, value):
    state = opening()
    original = state.model_dump_json()
    changed = state.abilities[0].model_copy(update={field: value})
    with pytest.raises(RuntimeProblem) as failure:
        propose(state, [changed])
    assert failure.value.code == "ABILITY_IDENTITY_CONFLICT"
    assert state.model_dump_json() == original


def test_stage_progress_keeps_identity_source_and_original_snapshot():
    state = opening()
    previous = state.abilities[0]
    progressed = propose(state, [previous.model_copy(update={"stage": "熟悉"})])
    assert progressed.abilities[0].stage == "熟悉" and previous.stage == "初学"
    for field in ("id", "name", "kind", "source", "description", "definition"):
        assert getattr(progressed.abilities[0], field) == getattr(previous, field)


@pytest.mark.parametrize("reference", [DefinitionRef(category="arts", key="object-control"), None])
def test_different_ability_has_its_own_identity_and_keeps_original(reference):
    state = opening()
    new = Ability(id="new-ability", name="领域检查本领", kind="SPELL", stage="初学",
                  source="合成的领域检查来源", description="只检查独立身份，不代表发生学习。", definition=reference)
    result = propose(state, [new])
    assert result.abilities == [state.abilities[0], new]
    assert state.abilities == [state.abilities[0]]


@pytest.mark.parametrize("category", ["items", "realms"])
def test_public_item_or_realm_cannot_be_used_as_a_learned_ability_definition(category):
    state = opening()
    new = Ability(id="new-ability", name="领域检查本领", kind="SPELL", stage="初学", source="领域检查",
                  description="类别边界检查", definition=DefinitionRef(category=category, key="unit-entry"))
    with pytest.raises(RuntimeProblem) as failure:
        apply_candidate(state, Candidate(scene_title="领域校验", narrative=["不构成学习。"],
                        change=Change(reason="类别校验", abilities=[new])),
                        [{"category": category, "key": "unit-entry", "revision": 1, "content_hash": "b" * 64}], turn=1)
    assert failure.value.code == "INVALID_ABILITY_DEFINITION"
    assert len(state.abilities) == 1


def test_unrelated_turn_does_not_repair_or_validate_unmodified_legacy_ability():
    state = opening()
    state.abilities[0].definition = DefinitionRef(category="items", key="legacy-definition")
    unchanged = propose(state, [])
    assert unchanged.abilities == state.abilities

