"""创角候选的工程边界；底层明确注入离线结果，不作为真实模型验收。"""

import json
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage
from test_identity import create_save, register
from test_langchain_gateway import ScriptedChatModel
from test_world_setting import SettingLibrary

from wenxian_runtime.api import create_app
from wenxian_runtime.creation_domain import NameSuggestions, PathSuggestions, SuggestionInput
from wenxian_runtime.creation_gateway import CreationGateway
from wenxian_runtime.domain import ActionInput, RuntimeProblem
from wenxian_runtime.gateway import LangChainGateway
from wenxian_runtime.generation_domain import GenerationConfig
from wenxian_runtime.identity_domain import IdentityPolicy
from wenxian_runtime.repository import ROOT

BASE = "/api/runtime/v1/account/creation/suggestions"


class OfflineSuggestions:
    """验证请求和事务，不访问外部模型；玩家运行时没有这种启动模式。"""

    def __init__(self):
        self.calls, self.contexts, self.fail = 0, [], False

    def generate(self, body, context, route, key):
        self.calls += 1
        self.contexts.append(context)
        if self.fail:
            raise ValueError("synthetic-private-error")
        if body.kind == "names":
            result = NameSuggestions(names=["林默", "沈青", "顾宁", "陆遥"])
        else:
            result = PathSuggestions(paths=[{"title": title, "origin": f"来自{origin}，初到此地。", "goal": goal}
                for title, origin, goal in [("寻处落脚", "附近山村", "先问清食宿条件"),
                                           ("出门见闻", "江边小镇", "先了解镇上的药材生意"),
                                           ("静心修行", "乡间", "先寻安静的落脚处")]])
        return result, {"input_tokens": 12, "output_tokens": 20, "total_tokens": 32, "raw": "never-store"}


@pytest.fixture
def creation_runtime(tmp_path, monkeypatch):
    monkeypatch.setenv("WENXIAN_MODEL_API_KEY", "synthetic-platform-key")
    generator = OfflineSuggestions()
    config = GenerationConfig.model_validate_json((ROOT / "resources/generation.local.json").read_text())
    app = create_app(tmp_path / "creation.sqlite3", LangChainGateway(model="never-called", key="never-sent"),
                     library_transport=httpx.MockTransport(SettingLibrary().respond),
                     identity_policy=IdentityPolicy(registrations_per_client=20),
                     generation_config=config, call_limit="unlimited", suggestion_gateway=generator)
    with TestClient(app) as client:
        account, _ = register(client)
        yield client, app, account, generator


def test_candidates_require_account_and_never_create_game_facts(creation_runtime):
    client, app, account, generator = creation_runtime
    body = {"request_id": "names-first", "kind": "names"}
    assert client.post(BASE, json=body).status_code == 202
    result = client.get(BASE + "/names-first").json()
    assert result["status"] == "COMPLETED" and len(result["result"]["names"]) == 4
    assert client.post(BASE, json=body).status_code == 202
    assert generator.calls == 1
    assert client.get("/api/runtime/v1/account/saves").json() == []
    with app.state.repo.connection() as c:
        assert c.execute("SELECT COUNT(*) FROM runtime_save").fetchone()[0] == 0
        assert c.execute("SELECT COUNT(*) FROM credit_grant").fetchone()[0] == 0
        assert c.execute("SELECT COUNT(*) FROM model_call").fetchone()[0] == 0
        row = dict(c.execute("SELECT * FROM creation_suggestion").fetchone())
        assert json.loads(row["usage_json"]) == {"input_tokens": 12, "output_tokens": 20, "total_tokens": 32}
        assert "synthetic-platform-key" not in str(row)
        assert app.state.repo.model_call_total(c) == 1
    assert client.post(BASE, json={**body, "gender": "女"}).status_code == 409
    client.post("/api/v1/auth/logout")
    assert client.get(BASE + "/names-first").status_code == 401
    register(client, "second@example.test")
    assert client.get(BASE + "/names-first").status_code == 404
    assert generator.calls == 1


def test_paths_bind_preview_and_only_selected_profile_is_committed(creation_runtime):
    client, app, account, generator = creation_runtime
    body = {"request_id": "paths-first", "kind": "paths", "name": "沈青"}
    assert client.post(BASE, json=body).status_code == 422
    assert client.post(BASE, json={**body, "world_setting_hash": "0" * 64}).status_code == 409
    preview = client.get("/api/runtime/v1/creation").json()
    assert client.post(BASE, json={**body, "world_setting_hash": preview["setting_hash"]}).status_code == 202
    result = client.get(BASE + "/paths-first").json()["result"]["paths"]
    assert generator.calls == 1 and len(result) == 3
    assert generator.contexts[0]["opening"]["location"] == "青溪镇·青木坊门前"
    assert generator.contexts[0]["setting_hash"] == preview["setting_hash"]
    assert client.get("/api/runtime/v1/account/saves").json() == []
    chosen = result[1]
    response = create_save(client, name="沈青", origin=chosen["origin"], goal=chosen["goal"])
    assert response.status_code == 201
    snapshot = response.json()["snapshot"]
    assert snapshot["state"]["origin"] == chosen["origin"] and snapshot["state"]["goal"] == chosen["goal"]
    assert snapshot["revision"] == 0 and snapshot["state"]["scene_title"] == "青木坊的来货"
    assert client.get("/api/v1/account/credits").json()["balance"]["spent"] == 0


def test_failed_generation_preserves_request_without_retry_or_points(creation_runtime):
    client, app, _, generator = creation_runtime
    generator.fail = True
    body = {"request_id": "names-failed", "kind": "names", "name": "亲写姓名"}
    assert client.post(BASE, json=body).status_code == 202
    result = client.get(BASE + "/names-failed").json()
    assert result["status"] == "FAILED" and result["result"] is None
    assert "synthetic-private-error" not in str(result)
    client.post(BASE, json=body)
    assert generator.calls == 1
    generator.fail = False
    client.post(BASE, json={**body, "request_id": "names-new-batch"})
    assert generator.calls == 2
    with app.state.repo.connection() as c:
        assert app.state.repo.model_call_total(c) == 2
        assert c.execute("SELECT COUNT(*) FROM credit_grant").fetchone()[0] == 0


def test_concurrent_same_id_only_calls_once_and_other_pending_batch_is_rejected(creation_runtime):
    _, app, account, generator = creation_runtime
    body = SuggestionInput(request_id="names-concurrent", kind="names")
    def reserve():
        return app.state.suggestions.reserve(account, body)[1]
    with ThreadPoolExecutor(max_workers=4) as pool:
        admitted = list(pool.map(lambda _: reserve(), range(8)))
    assert sum(admitted) == 1
    with pytest.raises(RuntimeProblem, match="还在生成"):
        app.state.suggestions.reserve(account, body.model_copy(update={"request_id": "another-batch"}))
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda _: app.state.suggestions.execute(account, body.request_id), range(8)))
    assert generator.calls == 1


def test_budget_includes_candidates_and_restart_does_not_reissue(creation_runtime):
    client, app, account, generator = creation_runtime
    client.post(BASE, json={"request_id": "names-counted", "kind": "names"})
    snapshot = create_save(client).json()["snapshot"]
    request, _ = app.state.repo.reserve(snapshot["save_id"], ActionInput(request_id="story-budget", expected_revision=0, action="仅预算工程检查"), account)
    with pytest.raises(RuntimeProblem, match="额度"):
        app.state.repo.spend_model_call(request, 1)
    row, _ = app.state.suggestions.reserve(account, SuggestionInput(request_id="names-interrupted", kind="names"))
    app.state.repo.recover_interrupted()
    assert app.state.suggestions.status(account, row["request_id"])["status"] == "FAILED"
    app.state.suggestions.execute(account, row["request_id"])
    assert generator.calls == 1
    app.state.suggestions.call_limit = 0
    client.post(BASE, json={"request_id": "names-no-budget", "kind": "names"})
    assert client.get(BASE + "/names-no-budget").json()["error"]["code"] == "MODEL_BUDGET"
    assert generator.calls == 1


def test_langchain_chain_parses_whole_json_without_repair(monkeypatch):
    from wenxian_runtime import creation_gateway
    model = ScriptedChatModel(responses=[AIMessage(content='{"names":["林默","沈青","顾宁","陆遥"]}',
        usage_metadata={"input_tokens": 7, "output_tokens": 4, "total_tokens": 11})])
    monkeypatch.setattr(creation_gateway, "DeepSeekJsonChatModel", lambda **kwargs: model)
    # 测试替身只用于 chain；使用标准分支的 bind 同样返回本地 ScriptedChatModel。
    monkeypatch.setattr(creation_gateway, "ChatOpenAI", lambda **kwargs: model)
    result, usage = CreationGateway().generate(SuggestionInput(request_id="chain-test", kind="names"), {},
        {"model": "offline", "base_url": "https://api.example.test", "tool_protocol": "deepseek-json"}, "never-sent")
    assert len(result.names) == 4 and usage["total_tokens"] == 11 and model.index == 1


@pytest.mark.parametrize("content", ["这是一份未按协议返回的普通建议", '{"names":["林默","林默","林默","林默"]}',
                                     '{"names":["林默","沈青","顾宁","陆遥"],"realm":"擅自附加"}'])
def test_invalid_model_result_is_not_repaired_or_accepted(monkeypatch, content):
    from wenxian_runtime import creation_gateway
    model = ScriptedChatModel(responses=[AIMessage(content=content)])
    monkeypatch.setattr(creation_gateway, "DeepSeekJsonChatModel", lambda **kwargs: model)
    with pytest.raises(ValueError):
        CreationGateway().generate(SuggestionInput(request_id="invalid-chain", kind="names"), {},
            {"model": "offline", "base_url": "https://api.example.test", "tool_protocol": "deepseek-json"}, "never-sent")
    assert model.index == 1
