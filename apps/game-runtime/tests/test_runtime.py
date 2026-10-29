"""独立临时库验证权限、事务、恢复和资料引用；无付费模型调用。"""

import secrets
import threading
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from fastapi.testclient import TestClient
from setting_library import setting_response

from wenxian_runtime.api import create_app
from wenxian_runtime.domain import ActionInput, Candidate, Change, DefinitionRef, Item, RuntimeProblem
from wenxian_runtime.gateway import DisabledGateway
from wenxian_runtime.library import LibraryReader
from wenxian_runtime.repository import Repository

HASH = "a" * 64
DEFINITION = {
    "key": "basic-breathing",
    "name": "基础吐纳法",
    "revision": 2,
    "content_hash": HASH,
    "entry_kind": "definition",
    "usage": "调息",
}


def library_response(request):
    setting = setting_response(request)
    if setting is not None:
        return setting
    if "revisions" in request.url.path:
        return httpx.Response(200, json={"revision": 2, "content_hash": HASH, "content": DEFINITION})
    return httpx.Response(200, json=DEFINITION)


class TestGateway:
    __test__ = False
    mode = "fixture"

    def __init__(self, fail=False):
        self.calls, self.fail = 0, fail

    def generate(self, state, action, library, history, spend, record=None):
        self.calls += 1
        if self.fail:
            raise ValueError("secret must not escape")
        library.get("arts", "basic-breathing")
        return Candidate(
            scene_title="已确认测试回应",
            narrative=[f"测试回应：{action}"],
            change=Change(reason="测试数据的明确本轮变化", condition="已歇息"),
        )


def new_session(client, **profile):
    setting_hash = client.get("/api/runtime/v1/creation").json()["setting_hash"]
    response = client.post(
        "/api/runtime/v1/sessions",
        headers={"Authorization": f"Bearer {secrets.token_urlsafe(32)}"},
        json={"opening_id": "qingmu-trial", "name": "林默", "goal": "在青溪镇落脚",
              "world_setting_hash": setting_hash, **profile},
    )
    assert response.status_code == 201
    token = response.request.headers["Authorization"].removeprefix("Bearer ")
    return {"token": token, **response.json()}


@pytest.fixture
def runtime(tmp_path):
    gateway = TestGateway()
    app = create_app(
        tmp_path / "test.sqlite3", gateway, library_transport=httpx.MockTransport(library_response)
    )
    with TestClient(app) as client:
        session = new_session(client)
        client.headers["Authorization"] = f"Bearer {session['token']}"
        yield client, app, gateway, session


def submit(client, revision=0, request_id="request-test-1", action="先问清条件"):
    return client.post(
        "/api/runtime/v1/actions",
        json={"request_id": request_id, "expected_revision": revision, "action": action},
    )


def test_atomic_commit_idempotency_and_exact_historical_status(runtime):
    client, app, gateway, session = runtime
    assert submit(client).status_code == 202
    first = client.get("/api/runtime/v1/save").json()
    assert first["revision"] == 1 and first["state"]["condition"] == "已歇息"
    assert first["state"]["definition_pins"]["arts:basic-breathing"]["revision"] == 2
    assert submit(client).status_code == 202
    assert gateway.calls == 1
    assert submit(client, request_id="request-test-1", action="另一行动").status_code == 409
    assert submit(client, revision=0, request_id="request-test-stale").status_code == 409
    assert submit(client, revision=1, request_id="request-test-2", action="再问来路").status_code == 202
    old = client.get("/api/runtime/v1/actions/request-test-1").json()
    assert old["snapshot"]["revision"] == 1
    assert client.get("/api/runtime/v1/save").json()["revision"] == 2
    rows = app.state.repo.history(session["snapshot"]["save_id"], limit=20)
    assert len(rows) == 3 and rows[1]["action"] == "先问清条件"
    practice = next(row for row in rows[1]["references"] if (row["category"], row["key"]) == ("arts", "basic-breathing"))
    assert practice["definition"]["usage"] == "调息"


def test_failure_preserves_save_and_retries_same_request(runtime):
    client, app, gateway, session = runtime
    before = client.get("/api/runtime/v1/save").json()["state"]
    gateway.fail = True
    submit(client)
    failure = client.get("/api/runtime/v1/actions/request-test-1").json()
    assert failure["status"] == "FAILED"
    assert "secret" not in str(failure)
    assert client.get("/api/runtime/v1/save").json()["state"] == before
    gateway.fail = False
    submit(client)
    assert client.get("/api/runtime/v1/save").json()["revision"] == 1
    assert len(app.state.repo.history(session["snapshot"]["save_id"], limit=20)) == 2


def test_test_sessions_cannot_read_each_others_save_or_requests(runtime):
    client, app, gateway, first = runtime
    submit(client)
    second = new_session(client)
    client.headers["Authorization"] = f"Bearer {second['token']}"
    assert client.get("/api/runtime/v1/save").json()["save_id"] != first["snapshot"]["save_id"]
    assert client.get("/api/runtime/v1/actions/request-test-1").json()["status"] == "UNKNOWN"
    assert client.get("/api/runtime/v1/history").json()[0]["turn"] == 0
    client.headers["Authorization"] = "Bearer invalid-token"
    assert client.get("/api/runtime/v1/save").status_code == 401
    del client.headers["Authorization"]
    assert submit(client).status_code == 401


def test_two_parallel_reservations_allow_one_action(runtime):
    client, app, gateway, session = runtime
    save_id = session["snapshot"]["save_id"]
    barrier = threading.Barrier(2)

    def reserve(i):
        barrier.wait()
        try:
            return app.state.repo.reserve(
                save_id, ActionInput(request_id=f"parallel-{i}", expected_revision=0, action="尝试")
            )[1]
        except RuntimeProblem as error:
            return error.code

    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(reserve, [1, 2]))
    assert sorted(str(r) for r in results) == ["ACTION_IN_PROGRESS", "True"]
    assert client.get("/api/runtime/v1/save").json()["revision"] == 0


def test_reopen_recovers_interruption_and_old_worker_cannot_commit(runtime):
    client, app, gateway, session = runtime
    save_id = session["snapshot"]["save_id"]
    request = ActionInput(request_id="interrupted-1", expected_revision=0, action="问清条件")
    old, _ = app.state.repo.reserve(save_id, request)
    app.state.repo.recover_interrupted()
    fresh, is_new = app.state.repo.reserve(save_id, request)
    assert is_new and fresh["attempt_id"] != old["attempt_id"]
    app.state.service.execute(old)
    assert app.state.repo.status(save_id, request.request_id)["status"] == "PROCESSING"
    app.state.service.execute(fresh)
    reopened = Repository(app.state.repo.path)
    reopened.migrate()
    assert reopened.save(save_id)["revision"] == 1
    assert reopened.status(save_id, request.request_id)["status"] == "COMMITTED"


def test_failed_commit_rolls_back_state_and_turn(runtime):
    client, app, gateway, session = runtime
    save_id = session["snapshot"]["save_id"]
    with app.state.repo.connection(write=True) as c:
        c.execute(
            "CREATE TRIGGER reject_turn BEFORE INSERT ON committed_turn WHEN NEW.turn>0 BEGIN SELECT RAISE(ABORT,'test rollback'); END"
        )
    submit(client)
    assert app.state.repo.save(save_id)["revision"] == 0
    assert len(app.state.repo.history(save_id)) == 1
    assert app.state.repo.status(save_id, "request-test-1")["status"] == "FAILED"


def test_unread_definition_not_granted_and_disabled_gateway_no_calls(runtime):
    client, app, gateway, session = runtime

    class UnreadGateway:
        mode = "fixture"

        def generate(self, *args):
            return Candidate(
                scene_title="试图获得",
                narrative=["未读定义"],
                change=Change(
                    reason="本轮测试",
                    items=[
                        Item(
                            id="bag",
                            name="储物袋",
                            category="器物",
                            status="完好",
                            quantity=1,
                            description="测试",
                            definition=DefinitionRef(category="items", key="storage-pouch"),
                        )
                    ],
                ),
            )

    app.state.service.gateway = UnreadGateway()
    submit(client)
    assert client.get("/api/runtime/v1/actions/request-test-1").json()["error"]["code"] == "UNREAD_DEFINITION"
    assert len(client.get("/api/runtime/v1/save").json()["state"]["items"]) == 1
    app.state.service.gateway = DisabledGateway()
    submit(client)
    assert (
        client.get("/api/runtime/v1/actions/request-test-1").json()["error"]["code"] == "MODEL_NOT_CONFIGURED"
    )
    assert client.get("/api/runtime/v1/save").json()["revision"] == 0


def test_model_budget_is_persistent_and_consumed_before_call(runtime):
    client, app, gateway, session = runtime
    request, _ = app.state.repo.reserve(
        session["snapshot"]["save_id"],
        ActionInput(request_id="budget-test", expected_revision=0, action="测试"),
    )
    with pytest.raises(RuntimeProblem, match="额度"):
        app.state.repo.spend_model_call(request, 0)
    app.state.repo.spend_model_call(request, 1)
    reopened = Repository(app.state.repo.path)
    with pytest.raises(RuntimeProblem, match="额度"):
        reopened.spend_model_call(request, 1)


def test_unlimited_total_keeps_every_call_and_per_attempt_boundary(runtime):
    """只检查调用计数与恢复边界；不生成故事或访问模型，不能作为玩法签收。"""
    client, app, gateway, session = runtime
    repo = app.state.repo
    for attempt in range(4):
        request, _ = repo.reserve(session["snapshot"]["save_id"], ActionInput(
            request_id="unlimited-budget", expected_revision=0, action="仅检查调用预算边界"))
        for _ in range(4):
            repo.spend_model_call(request, None)
        with pytest.raises(RuntimeProblem, match="额度"):
            repo.spend_model_call(request, None)
        repo.fail(request, "BOUNDARY_CHECK", "仅检查计数，不生成故事")
    reopened = Repository(repo.path)
    with reopened.connection() as c:
        assert c.execute("SELECT COUNT(*) FROM model_call").fetchone()[0] == 16
    with pytest.raises(RuntimeProblem) as failure:
        reopened.spend_model_call(request, None)
    assert failure.value.code == "STALE_ATTEMPT"


@pytest.mark.parametrize("value,expected", [("0", 0), ("7", 7), ("unlimited", None)])
def test_configured_total_budget_requires_an_explicit_unlimited_value(tmp_path, monkeypatch, value, expected):
    monkeypatch.setenv("WENXIAN_MODEL_CALL_LIMIT", value)
    app = create_app(tmp_path / "budget-config.sqlite3")
    assert app.state.service.call_limit == expected


@pytest.mark.parametrize("value", ["-1", "invalid"])
def test_invalid_configured_budget_does_not_enable_calls(tmp_path, monkeypatch, value):
    monkeypatch.setenv("WENXIAN_MODEL_CALL_LIMIT", value)
    with pytest.raises(ValueError):
        create_app(tmp_path / "invalid-budget.sqlite3")


def test_revision_pin_uses_history_content(runtime):
    client, app, gateway, session = runtime
    submit(client)
    from wenxian_runtime.domain import GameState

    state = GameState.model_validate(client.get("/api/runtime/v1/save").json()["state"])
    seen = []

    def pinned(request):
        seen.append(request.url.path)
        return library_response(request)

    reader = LibraryReader("http://library.test", state, httpx.MockTransport(pinned))
    try:
        assert reader.get("arts", "basic-breathing")["revision"] == 2
        assert seen == ["/api/library/v1/revisions/arts:basic-breathing:2/"]
    finally:
        reader.close()


def test_api_ignores_attempt_to_overwrite_identity_or_revision(runtime):
    client, app, gateway, session = runtime
    assert (
        client.post(
            "/api/runtime/v1/actions",
            json={
                "request_id": "bad-input",
                "expected_revision": 0,
                "action": "test",
                "state": {"name": "偷换身份"},
            },
        ).status_code
        == 422
    )
    assert client.get("/api/runtime/v1/save").json()["revision"] == 0


def test_process_lock_and_restart(tmp_path):
    path = tmp_path / "restart.sqlite3"
    first = create_app(path, TestGateway())
    with TestClient(first) as client:
        session = new_session(client)
        save_id = session["snapshot"]["save_id"]
        first.state.repo.reserve(
            save_id, ActionInput(request_id="restart-test", expected_revision=0, action="暂停前的行动")
        )
        with pytest.raises(BlockingIOError):
            with TestClient(create_app(path, TestGateway())):
                pass
    with TestClient(create_app(path, TestGateway())) as reopened:
        reopened.headers["Authorization"] = f"Bearer {session['token']}"
        status = reopened.get("/api/runtime/v1/actions/restart-test").json()
        assert status["status"] == "FAILED" and status["error"]["code"] == "INTERRUPTED"


def test_real_mode_cannot_treat_simulation_as_game_history(runtime):
    client, app, gateway, session = runtime
    submit(client)
    gateway.mode = "langchain"
    submit(client, revision=1, request_id="real-after-simulation")
    status = client.get("/api/runtime/v1/actions/real-after-simulation").json()
    assert status["error"]["code"] == "SIMULATED_SAVE"
    assert gateway.calls == 1
    assert client.get("/api/runtime/v1/save").json()["revision"] == 1


def test_explicit_upgrade_preserves_existing_opening(tmp_path):
    import sqlite3

    from wenxian_runtime.domain import opening
    from wenxian_runtime.repository import ROOT, dump, now

    path = tmp_path / "old-schema.sqlite3"
    legacy_state = opening().model_dump()
    for key in ("gender", "origin", "story_preference"):
        legacy_state.pop(key)
    with sqlite3.connect(path) as connection:
        connection.executescript((ROOT / "migrations/001_runtime.sql").read_text())
        connection.execute("CREATE TABLE schema_migration(name TEXT PRIMARY KEY)")
        connection.execute("INSERT INTO schema_migration VALUES ('001_runtime.sql')")
        connection.execute("INSERT INTO runtime_session VALUES ('session','hash',?)", (now(),))
        connection.execute(
            "INSERT INTO runtime_save VALUES ('save','session',0,?)", (dump(legacy_state),)
        )
        connection.execute(
            "INSERT INTO committed_turn VALUES ('save',0,NULL,'开局',?,?,?,'opening',?)",
            (dump({"narrative": ["已有开局"]}), dump(legacy_state), "[]", now()),
        )
        connection.execute(
            "INSERT INTO action_request VALUES ('save','old-call',0,'已有行动','FAILED','old-attempt','TEST','原行动保留',?)",
            (now(),),
        )
        connection.execute(
            "INSERT INTO model_call VALUES (1,'save','old-call','old-attempt',?)", (now(),)
        )
    repo = Repository(path)
    repo.migrate()
    from wenxian_runtime.domain import GameState

    assert repo.save("save")["state"] == legacy_state
    assert GameState.model_validate(legacy_state).origin == ""
    assert repo.history("save")[0]["result"]["narrative"] == ["已有开局"]
    with repo.connection() as connection:
        assert connection.execute("SELECT generation_audit_json FROM committed_turn").fetchone()[0] == "{}"
        assert connection.execute("SELECT COUNT(*) FROM schema_migration").fetchone()[0] == 12
        assert connection.execute("SELECT COUNT(*) FROM save_world_setting").fetchone()[0] == 0
        assert connection.execute("SELECT COUNT(*) FROM account_save").fetchone()[0] == 0
        old_call = connection.execute("SELECT * FROM model_call").fetchone()
        assert old_call["outcome"] == "RESERVED" and old_call["duration_ms"] is None
        assert old_call["usage_json"] == "{}" and old_call["validation_errors_json"] == "[]"
    # 再次迁移不重置旧调用预算；仅代码回退也不删除已有审计及存档。
    repo.migrate()
    with pytest.raises(RuntimeProblem, match="额度"):
        retry, _ = repo.reserve(
            "save", ActionInput(request_id="old-call", expected_revision=0, action="已有行动")
        )
        repo.spend_model_call(retry, 1)


def test_model_call_outcomes_only_persist_safe_usage(runtime):
    client, app, gateway, session = runtime
    request, _ = app.state.repo.reserve(
        session["snapshot"]["save_id"],
        ActionInput(request_id="outcome-test", expected_revision=0, action="测试"),
    )
    first = app.state.repo.spend_model_call(request, 2)
    app.state.repo.record_model_call(
        first,
        120,
        usage={"input_tokens": 30, "output_tokens": 10, "total_tokens": 40, "secret": "never stored"},
    )
    second = app.state.repo.spend_model_call(request, 2)
    app.state.repo.record_model_call(second, 20, error_type="AuthenticationError", provider_status=401)
    with app.state.repo.connection() as connection:
        rows = connection.execute("SELECT * FROM model_call ORDER BY id").fetchall()
    assert rows[0]["outcome"] == "SUCCESS" and rows[0]["duration_ms"] == 120
    assert "secret" not in rows[0]["usage_json"]
    assert rows[1]["outcome"] == "FAILED" and rows[1]["provider_status"] == 401


def test_information_position_normalizes_without_changing_or_merging_facts():
    from pydantic import ValidationError

    info = {"kind": "statement", "subject": "食宿条件", "text": "掌柜开价一餐一夜", "source": "掌柜口述"}
    result = Candidate.model_validate(
        {
            "scene_title": "协商",
            "narrative": ["尚未接受"],
            "change": {"reason": "本轮询问", "information": [info]},
        }
    )
    assert result.information[0].kind == "statement"
    assert result.information[0].text == info["text"]
    assert "information" not in result.change.model_dump()
    with pytest.raises(ValidationError):
        Candidate.model_validate(
            {
                "scene_title": "协商",
                "narrative": ["尚未接受"],
                "information": [{**info, "kind": "fact"}],
                "change": {"reason": "本轮询问", "information": [info]},
            }
        )
    with pytest.raises(ValidationError):
        Candidate.model_validate(
            {
                "scene_title": "协商",
                "narrative": ["尚未接受"],
                "change": {"reason": "本轮询问", "information": [info], "arbitrary_permission": True},
            }
        )


def test_retry_attempt_keeps_global_budget_but_not_spent_previous_attempt_slots(runtime):
    client, app, gateway, session = runtime
    body = ActionInput(request_id="retry-budget", expected_revision=0, action="询问")
    request, _ = app.state.repo.reserve(session["snapshot"]["save_id"], body)
    for _ in range(4):
        app.state.repo.spend_model_call(request, 5)
    app.state.repo.fail(request, "TEST_FAILURE", "原行动保留")
    retry, _ = app.state.repo.reserve(session["snapshot"]["save_id"], body)
    assert app.state.repo.spend_model_call(retry, 5)
    with pytest.raises(RuntimeProblem):
        app.state.repo.spend_model_call(retry, 5)


def test_creation_profile_summary_and_ownership(runtime):
    client, app, gateway, first = runtime
    created = new_session(client, name="沈青", goal="寻找失散的师姐", gender="女", origin="自山村出发", story_preference="多一些人物交流")
    token = created["token"]
    profile = created["snapshot"]["state"]
    assert profile["name"] == "沈青" and profile["goal"] == "寻找失散的师姐"
    assert profile["gender"] == "女" and profile["origin"] == "自山村出发"
    assert profile["story_preference"] == "多一些人物交流"
    assert profile["items"] == first["snapshot"]["state"]["items"]
    client.headers["Authorization"] = f"Bearer {token}"
    summary = client.get("/api/runtime/v1/save/summary").json()
    assert summary["name"] == "沈青" and summary["revision"] == 0
    assert "narrative" not in summary and "state" not in summary
    submit(client)
    saved = client.get("/api/runtime/v1/save").json()
    assert saved["state"]["origin"] == "自山村出发"
    assert client.get("/api/runtime/v1/save/summary").json()["revision"] == 1
    client.headers["Authorization"] = f"Bearer {first['token']}"
    assert client.get("/api/runtime/v1/save").json()["revision"] == 0
    client.headers["Authorization"] = "Bearer missing-capability"
    assert client.get("/api/runtime/v1/save/summary").status_code == 401


def test_creation_retry_concurrency_and_changed_profile(runtime):
    client, app, gateway, first = runtime
    from wenxian_runtime.domain import CharacterCreation
    from wenxian_runtime.session_service import SessionService

    token = secrets.token_urlsafe(32)
    profile = CharacterCreation(opening_id="qingmu-trial", name="顾川", goal="先安顿下来",
                                world_setting_hash=client.get("/api/runtime/v1/creation").json()["setting_hash"])
    service = SessionService(app.state.repo, app.state.worlds)
    with ThreadPoolExecutor(max_workers=2) as pool:
        saves = list(pool.map(lambda _: service.create(profile, token), range(2)))
    assert saves[0] == saves[1]
    with app.state.repo.connection() as c:
        assert c.execute("SELECT COUNT(*) FROM committed_turn WHERE save_id=?", (saves[0],)).fetchone()[0] == 1
        assert c.execute("SELECT COUNT(*) FROM model_call").fetchone()[0] == 0
    client.headers["Authorization"] = f"Bearer {token}"
    submit(client)
    body = profile.model_dump()
    retried = client.post("/api/runtime/v1/sessions", json=body).json()["snapshot"]
    assert retried["save_id"] == saves[0] and retried["revision"] == 1
    reopened = SessionService(Repository(app.state.repo.path), app.state.worlds)
    assert reopened.create(profile, token) == saves[0]
    conflict = client.post("/api/runtime/v1/sessions", json={**body, "goal": "换一个目标"})
    assert conflict.status_code == 409 and conflict.json()["error"]["code"] == "CREATION_CONFLICT"
    assert client.get("/api/runtime/v1/save").json()["state"]["goal"] == "先安顿下来"


def test_creation_validation_and_preview_do_not_create_or_spend(runtime):
    client, app, gateway, first = runtime
    preview = client.get("/api/runtime/v1/creation").json()
    assert preview["realm"] == first["snapshot"]["state"]["realm"]
    body = {"opening_id": "qingmu-trial", "name": "沈青", "goal": "寻找朋友"}
    client.headers["Authorization"] = f"Bearer {secrets.token_urlsafe(32)}"
    for patch in ({"name": " "}, {"goal": " "}, {"goal": "字" * 241}, {"opening_id": "invented"}, {"realm": "无条件高阶"}):
        assert client.post("/api/runtime/v1/sessions", json={**body, **patch}).status_code == 422
    client.headers["Authorization"] = "Bearer short"
    assert client.post("/api/runtime/v1/sessions", json=body).status_code == 400
    del client.headers["Authorization"]
    assert client.post("/api/runtime/v1/sessions", json=body).status_code == 401
    assert client.post("/api/runtime/v1/sessions").status_code == 422
    with app.state.repo.connection() as c:
        assert c.execute("SELECT COUNT(*) FROM runtime_save").fetchone()[0] == 1
        assert c.execute("SELECT COUNT(*) FROM model_call").fetchone()[0] == 0
    assert gateway.calls == 0
