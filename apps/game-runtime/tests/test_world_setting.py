"""资料设定的来源、事务、版本与上下文工程检查；不运行玩家模拟续写或外部模型。"""

import hashlib
import json
import secrets
import sqlite3
from copy import deepcopy

import httpx
import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage
from pydantic import Field
from test_identity import BASE, register
from test_langchain_gateway import ScriptedChatModel

from wenxian_runtime.api import create_app
from wenxian_runtime.domain import Candidate, DefinitionRef, RuntimeProblem, opening
from wenxian_runtime.gateway import LangChainGateway
from wenxian_runtime.identity_domain import IdentityPolicy
from wenxian_runtime.library import LibraryReader
from wenxian_runtime.world_domain import WorldProfile, WorldSetting


class SettingLibrary:
    """只在单元测试使用的合成资料服务；修改当前版不修改既有快照。"""

    def __init__(self):
        self.calls, self.offline = [], False
        self.entries = {}
        for category, key, name in [
            ("realms", "lianqi", "合成炼气"), ("realms", "zhuji", "合成筑基"),
            ("arts", "basic-breathing", "合成吐纳功"), ("items", "medicinal-spirit-herb", "合成药材总类"),
            ("arts", "object-control", "合成控物术"), ("items", "storage-pouch", "合成储物袋"),
            ("items", "basic-protection-talisman", "合成护身符"),
        ]:
            self.entries[f"{category}:{key}"] = {
                "key": key, "name": name, "revision": 2, "entry_kind": "overview",
                "description": name + "的明确单元说明", "usage": "背景用途",
                "limitations": "不能据此授予角色能力或保证成功", "source": "单元合成资料",
                "adaptation": "不进入玩家正文的编辑记录",
            }
        self.revisions = {(key, 2): deepcopy(data) for key, data in self.entries.items()}

    def respond(self, request):
        self.calls.append(str(request.url))
        if self.offline:
            return httpx.Response(503)
        parts = request.url.path.rstrip("/").split("/")
        historical = parts[-2] == "revisions"
        if historical:
            category, key, revision = parts[-1].split(":")
            data = deepcopy(self.revisions.get((f"{category}:{key}", int(revision))))
        else:
            data = deepcopy(self.entries.get(":".join(parts[-2:])))
        if data is None:
            return httpx.Response(404)
        data["content_hash"] = hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()
        return httpx.Response(200, json={"revision": data["revision"], "content_hash": data["content_hash"],
                                       "content": data} if historical else data)


class InspectingGateway:
    """仅检查候选和提交边界，不作为任何玩法结果。"""

    mode = "fixture"

    def __init__(self):
        self.calls, self.contexts, self.practices = 0, [], []
        self.read_practice = True
        self.claim_unread = False

    def generate(self, state, action, library, history, spend, record=None):
        self.calls += 1
        self.contexts.append(library.world_context)
        used = []
        if self.read_practice:
            self.practices.append(library.get("arts", "basic-breathing"))
            used.append(DefinitionRef(category="arts", key="basic-breathing"))
        if self.claim_unread:
            used.append(DefinitionRef(category="arts", key="firebolt"))
        return Candidate(scene_title="单元事务检查", narrative=["仅验证设定输入与持久化。"], used_definitions=used)


def setting_app(tmp_path, library, gateway=None, **kwargs):
    return create_app(tmp_path / "world.sqlite3", gateway or InspectingGateway(),
                      library_transport=httpx.MockTransport(library.respond),
                      identity_policy=IdentityPolicy(registrations_per_client=20), **kwargs)


def character(client):
    return {"opening_id": "qingmu-trial", "name": "沈青", "goal": "理解修行",
            "world_setting_hash": client.get("/api/runtime/v1/creation").json()["setting_hash"]}


def create_trial(client, body=None):
    client.headers["Authorization"] = f"Bearer {secrets.token_urlsafe(32)}"
    return client.post("/api/runtime/v1/sessions", json=body or character(client))


def model_count(repo):
    with repo.connection() as c:
        return c.execute("SELECT COUNT(*) FROM model_call").fetchone()[0]


def table_count(repo, table):
    with repo.connection() as c:
        return c.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0]


def test_preview_reads_real_path_and_requires_explicit_confirmation(tmp_path):
    library = SettingLibrary()
    app = setting_app(tmp_path, library)
    with TestClient(app) as client:
        preview = client.get("/api/runtime/v1/creation").json()
        assert preview["realm"] == "合成炼气初期" and preview["abilities"] == ["合成吐纳功"]
        assert len(library.calls) == 7 and len(preview["setting_hash"]) == 64
        assert model_count(app.state.repo) == table_count(app.state.repo, "runtime_save") == 0
        response = create_trial(client, {"opening_id": "qingmu-trial", "name": "沈青", "goal": "理解修行"})
        assert response.status_code == 409 and response.json()["code"] == "SETTING_CONFIRMATION_REQUIRED"
        library.offline = True
        assert client.get("/api/runtime/v1/creation").status_code == 503
        assert table_count(app.state.repo, "save_world_setting") == 0


def test_new_opening_uses_library_names_and_records_ownership_separately(tmp_path):
    app = setting_app(tmp_path, SettingLibrary())
    with TestClient(app) as client:
        result = create_trial(client).json()["snapshot"]
        setting = app.state.worlds.saved(result["save_id"])
        assert result["state"]["realm"] == "合成炼气初期"
        assert result["state"]["abilities"][0]["name"] == "合成吐纳功"
        assert result["state"]["abilities"][0]["description"] == "合成吐纳功的明确单元说明"
        assert result["state"]["abilities"][0]["source"] == "玩家确认的本局开篇"
        assert [item["id"] for item in result["state"]["items"]] == ["clothes"]
        assert "items:medicinal-spirit-herb" in setting.definitions
        assert result["state"]["definition_pins"] == {}
        assert setting.schema_version == "library-setting-2" and model_count(app.state.repo) == 0


def test_changed_setting_rejects_unconfirmed_opening_without_partial_rows(tmp_path):
    library = SettingLibrary()
    from wenxian_runtime.repository import ROOT
    profile = json.loads((ROOT / "resources/world-profile.json").read_text())
    path = tmp_path / "profile.json"
    path.write_text(json.dumps(profile))
    app = setting_app(tmp_path, library, world_profile_path=path)
    with TestClient(app) as client:
        body = character(client)
        library.entries["realms:lianqi"]["name"] = "新版合成炼气"
        library.entries["realms:lianqi"]["revision"] = 3
        library.revisions[("realms:lianqi", 3)] = deepcopy(library.entries["realms:lianqi"])
        profile["adopted_definitions"][0]["revision"] = 3
        path.write_text(json.dumps(profile))
        rejected = create_trial(client, body)
        assert rejected.status_code == 409 and rejected.json()["code"] == "SETTING_CHANGED"
        for table in ("runtime_session", "runtime_save", "committed_turn", "save_world_setting"):
            assert table_count(app.state.repo, table) == 0
        body["world_setting_hash"] = client.get("/api/runtime/v1/creation").json()["setting_hash"]
        saved = client.post("/api/runtime/v1/sessions", json=body).json()["snapshot"]
        assert saved["state"]["realm"] == "新版合成炼气初期"


@pytest.mark.parametrize("account", [False, True])
def test_completed_creation_recovery_does_not_need_library(tmp_path, account):
    library = SettingLibrary()
    app = setting_app(tmp_path, library)
    with TestClient(app) as client:
        profile = character(client)
        if account:
            register(client)
            path, body = BASE, {"request_id": "world-creation-001", "character": profile}
        else:
            client.headers["Authorization"] = f"Bearer {secrets.token_urlsafe(32)}"
            path, body = "/api/runtime/v1/sessions", profile
        first = client.post(path, json=body)
        assert first.status_code == 201
        library.offline = True
        calls = len(library.calls)
        retry = client.post(path, json=body)
        assert retry.status_code == 201 and retry.json() == first.json()
        assert len(library.calls) == calls and table_count(app.state.repo, "save_world_setting") == 1


def test_bound_background_and_practice_stay_fixed_when_library_changes_and_goes_offline(tmp_path):
    library, gateway = SettingLibrary(), InspectingGateway()
    app = setting_app(tmp_path, library, gateway)
    with TestClient(app) as client:
        snapshot = create_trial(client).json()["snapshot"]
        before = app.state.worlds.saved(snapshot["save_id"])
        library.entries["arts:basic-breathing"].update(name="不得替换旧卷", revision=3)
        library.offline = True
        calls = len(library.calls)
        assert client.post("/api/runtime/v1/actions", json={
            "request_id": "world-action-001", "expected_revision": 0, "action": "仅验证持久化",
        }).status_code == 202
        after = app.state.repo.save(snapshot["save_id"])
        assert after["revision"] == 1 and gateway.practices[0]["definition"]["name"] == "合成吐纳功"
        assert gateway.contexts[0]["setting_hash"] == before.digest()
        assert [row["key"] for row in gateway.contexts[0]["background_definitions"]] == ["lianqi", "zhuji"]
        assert len(library.calls) == calls and model_count(app.state.repo) == 0
        with app.state.repo.connection() as c:
            audit = json.loads(c.execute("SELECT generation_audit_json FROM committed_turn WHERE turn=1").fetchone()[0])
        assert audit["world_setting_hash"] == before.digest()
        assert audit["declared_definition_uses"] == [{"category": "arts", "key": "basic-breathing"}]


def test_setting_binding_failure_rolls_back_whole_creation(tmp_path):
    app = setting_app(tmp_path, SettingLibrary())
    with TestClient(app) as client:
        body = character(client)
        with app.state.repo.connection(write=True) as c:
            c.execute("CREATE TRIGGER reject_world BEFORE INSERT ON save_world_setting BEGIN SELECT RAISE(ABORT,'unit rollback'); END")
        with pytest.raises(sqlite3.IntegrityError):
            create_trial(client, body)
        for table in ("runtime_session", "runtime_save", "committed_turn", "save_world_setting"):
            assert table_count(app.state.repo, table) == 0


def test_legacy_save_is_not_rebound_or_rewritten(tmp_path):
    app = setting_app(tmp_path, SettingLibrary())
    with TestClient(app):
        state = opening()
        save_id = app.state.repo.create_session(secrets.token_urlsafe(32), state, "legacy-unit")
        before = app.state.repo.save(save_id), app.state.repo.history(save_id)
        app.state.repo.migrate()
        assert app.state.worlds.saved(save_id) is None
        assert (app.state.repo.save(save_id), app.state.repo.history(save_id)) == before
        assert table_count(app.state.repo, "save_world_setting") == 0


def test_damaged_setting_stops_before_generation_and_preserves_state(tmp_path):
    gateway = InspectingGateway()
    app = setting_app(tmp_path, SettingLibrary(), gateway)
    with TestClient(app) as client:
        before = create_trial(client).json()["snapshot"]
        with app.state.repo.connection(write=True) as c:
            c.execute("UPDATE save_world_setting SET setting_json='{}'")
        client.post("/api/runtime/v1/actions", json={
            "request_id": "damaged-world-01", "expected_revision": 0, "action": "仅检查失败边界",
        })
        status = client.get("/api/runtime/v1/actions/damaged-world-01").json()
        assert status["status"] == "FAILED" and status["error"]["code"] == "WORLD_SETTING_UNREADABLE"
        assert gateway.calls == model_count(app.state.repo) == 0
        assert app.state.repo.save(before["save_id"])["state"] == before["state"]


def test_declaring_unprovided_definition_does_not_commit(tmp_path):
    gateway = InspectingGateway()
    gateway.claim_unread = True
    app = setting_app(tmp_path, SettingLibrary(), gateway)
    with TestClient(app) as client:
        saved = create_trial(client).json()["snapshot"]
        client.post("/api/runtime/v1/actions", json={
            "request_id": "unread-world-001", "expected_revision": 0, "action": "仅检查引用边界",
        })
        assert client.get("/api/runtime/v1/actions/unread-world-001").json()["error"]["code"] == "UNREAD_DEFINITION"
        assert app.state.repo.save(saved["save_id"])["revision"] == 0
        assert len(app.state.repo.history(saved["save_id"])) == 1


def test_reference_reads_saved_rules_and_does_not_repin_or_grant(tmp_path):
    library = SettingLibrary()
    app = setting_app(tmp_path, library)
    with TestClient(app) as client:
        saved = create_trial(client).json()["snapshot"]
        before = app.state.repo.save(saved["save_id"]), app.state.repo.history(saved["save_id"])
        library.offline = True
        calls = len(library.calls)
        overview = client.get("/api/runtime/v1/reference").json()["world"]
        assert [section["title"] for section in overview["sections"]] == ["本局开篇", "合成炼气", "合成筑基"]
        assert "不能据此授予角色能力或保证成功" in str(overview)
        assert "不进入玩家正文的编辑记录" not in str(overview)
        detail = client.get("/api/runtime/v1/reference/entries/arts/basic-breathing").json()
        assert detail["scope"] == "save" and detail["revision"] == 2
        assert len(library.calls) == calls and model_count(app.state.repo) == 0
        assert (app.state.repo.save(saved["save_id"]), app.state.repo.history(saved["save_id"])) == before


def test_actual_langchain_payload_contains_bound_rules_without_promoting_opening_to_now(tmp_path, monkeypatch):
    class CapturingModel(ScriptedChatModel):
        seen: list = Field(default_factory=list)

        def _generate(self, messages, stop=None, run_manager=None, **kwargs):
            self.seen.extend(messages)
            return super()._generate(messages, stop, run_manager, **kwargs)

    model = CapturingModel(responses=[AIMessage(content="", tool_calls=[{
        "name": "Candidate", "id": "unit-candidate",
        "args": {"scene_title": "单元协议", "narrative": ["不作为玩法证据。"],
                 "used_definitions": [{"category": "realms", "key": "lianqi"}]},
    }])])
    import langchain_openai
    monkeypatch.setattr(langchain_openai, "ChatOpenAI", lambda **kwargs: model)
    app = setting_app(tmp_path, SettingLibrary(), LangChainGateway(model="offline", key="never-sent"), call_limit=1)
    with TestClient(app) as client:
        saved = create_trial(client).json()["snapshot"]
        client.post("/api/runtime/v1/actions", json={
            "request_id": "world-payload-01", "expected_revision": 0, "action": "仅检查输入序列化",
        })
        assert client.get("/api/runtime/v1/actions/world-payload-01").json()["status"] == "COMMITTED"
        context = json.loads(next(message.content for message in model.seen if isinstance(message, HumanMessage)))
        assert context["world_setting"]["setting_hash"] == app.state.worlds.saved(saved["save_id"]).digest()
        assert context["world_setting"]["opening_premise"]["confirmed_at_turn"] == 0
        assert "confirmed_state" in context and len(context["world_setting"]["background_definitions"]) == 2
        assert context["definitions"] == []
        assert model_count(app.state.repo) == 1


def test_reviewed_revision_is_used_even_when_public_current_changes(tmp_path):
    library = SettingLibrary()
    app = setting_app(tmp_path, library)
    original = app.state.worlds.current()
    library.entries["items:basic-protection-talisman"].update(name="未审定的当前新版", revision=3)
    reviewed = app.state.worlds.current()
    assert reviewed.digest() == original.digest()
    assert all("/revisions/" in path for path in library.calls)
    assert reviewed.definitions["items:basic-protection-talisman"]["revision"] == 2


def test_narrative_lookup_only_returns_adopted_frozen_candidates_and_does_not_pin(tmp_path):
    library = SettingLibrary()
    library.revisions[("arts:object-control", 2)]["aliases"] = ["单元御物"]
    setting = setting_app(tmp_path, library).state.worlds.current()
    reader = LibraryReader("http://not-used.test", frozen_definitions=setting.definitions,
                           adopted_definitions=setting.profile.adopted_keys())
    try:
        assert reader.search("arts", "单元御物")[0]["key"] == "object-control"
        assert reader.search("recipes", "回春丹") == [] and reader.reads == {}
        with pytest.raises(RuntimeProblem) as failure:
            reader.get("items", "huichun-dan")
        assert failure.value.code == "DEFINITION_NOT_ADOPTED" and reader.reads == {}
        assert reader.get("items", "storage-pouch")["revision"] == 2
    finally:
        reader.close()


def test_legacy_setting_hash_and_serialized_shape_remain_exact(tmp_path):
    new = setting_app(tmp_path, SettingLibrary()).state.worlds.current()
    profile = new.profile.model_dump(mode="json")
    profile.pop("adopted_definitions")
    profile.pop("item_use_rules")
    profile["revision"] = 1
    legacy = WorldProfile.model_validate(profile)
    payload = {"schema_version": "library-setting-1", "profile": profile,
               "definitions": {f"{ref.category}:{ref.key}": new.definitions[f"{ref.category}:{ref.key}"]
                               for ref in legacy.references()}}
    expected = hashlib.sha256(json.dumps(payload, ensure_ascii=False, sort_keys=True,
                                         separators=(",", ":")).encode()).hexdigest()
    restored = WorldSetting.model_validate_json(json.dumps(payload))
    assert restored.digest() == expected and restored.model_dump(mode="json") == payload
    assert restored.profile.adopted_keys() is None and restored.profile.item_use_rules == []


@pytest.mark.parametrize("damage", ["missing", "duplicate", "revision", "foreign-rule"])
def test_invalid_adoption_or_rule_cannot_be_saved(tmp_path, damage):
    from pydantic import ValidationError
    payload = setting_app(tmp_path, SettingLibrary()).state.worlds.current().model_dump(mode="json")
    profile = payload["profile"]
    if damage == "missing":
        profile["adopted_definitions"].pop(0)
    elif damage == "duplicate":
        profile["adopted_definitions"].append(profile["adopted_definitions"][0])
    elif damage == "revision":
        profile["adopted_definitions"][0]["revision"] = 99
    else:
        profile["item_use_rules"][0]["definition"]["key"] = "not-adopted"
    with pytest.raises(ValidationError):
        WorldSetting.model_validate(payload)


def test_library_cannot_substitute_a_different_historical_revision():
    def wrong_version(request):
        return httpx.Response(200, json={"revision": 3, "content_hash": "a" * 64,
                                       "content": {"key": "lianqi", "name": "错误修订"}})
    reader = LibraryReader("http://unit.test", transport=httpx.MockTransport(wrong_version))
    try:
        with pytest.raises(RuntimeProblem) as failure:
            reader.get("realms", "lianqi", revision=2)
        assert failure.value.code == "LIBRARY_UNAVAILABLE" and reader.reads == {}
    finally:
        reader.close()
