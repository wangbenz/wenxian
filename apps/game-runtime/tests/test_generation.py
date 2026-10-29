"""隔离推演/额度测试：网关全部为离线替身，不代表提供商可用或真实玩法通过。"""

import json
import secrets
from concurrent.futures import ThreadPoolExecutor

import httpx
import pytest
from fastapi.testclient import TestClient
from test_identity import BASE, create_save, register
from test_runtime import library_response

from wenxian_runtime.api import create_app
from wenxian_runtime.credential_cipher import CredentialCipher
from wenxian_runtime.domain import (
    ActionInput,
    Candidate,
    Change,
    DefinitionRef,
    Item,
    ItemEvent,
    ItemTransition,
    ItemUseEvent,
    RuntimeProblem,
)
from wenxian_runtime.generation_domain import GenerationConfig
from wenxian_runtime.identity_domain import IdentityPolicy

KEY = "synthetic-player-key-not-a-real-credential"


class OfflinePaidGateway:
    """只驱动实际预占和提交代码，不访问网络；mode 仅用于覆盖计费路径。"""

    mode = "langchain"

    def __init__(self):
        self.fail = False
        self.calls = 0
        self.routes = []

    def factory(self, route, key):
        self.routes.append({**route, "test_key_matches": key in {KEY, "synthetic-platform-secret"}})
        return self

    def generate(self, state, action, library, history, spend, record=None):
        call_id = spend()
        self.calls += 1
        if self.fail:
            record(call_id, 1, error_type="OfflineTestFailure")
            raise RuntimeProblem("OFFLINE_FAILURE", "离线测试失败")
        record(call_id, 1, usage={"input_tokens": 1, "output_tokens": 1, "total_tokens": 2})
        return Candidate(scene_title="离线提交验证", narrative=["仅用于检查事务。"])


def configuration(grant=5, cost=4):
    return GenerationConfig.model_validate({
        "opening_grant": grant,
        "providers": [{"code": "EXAMPLE", "name": "测试服务", "base_url": "https://api.example.test/v1",
                       "personal_enabled": True, "platform_key_env": "WENXIAN_OFFLINE_PLATFORM_KEY",
                       "models": [{"code": "GENERAL", "name": "通用", "api_model": "private-model-identity"}]}],
        "tiers": [{"code": "LIGHT", "name": "轻量", "point_cost": cost, "provider": "EXAMPLE", "model": "GENERAL"}],
        "default_tier": "LIGHT",
    })


@pytest.fixture
def paid_runtime(tmp_path, monkeypatch):
    monkeypatch.setenv("WENXIAN_OFFLINE_PLATFORM_KEY", "synthetic-platform-secret")
    moment = [1800000000]
    gateway = OfflinePaidGateway()
    cipher = CredentialCipher(secrets.token_bytes(32))
    app = create_app(tmp_path / "paid-path.sqlite3", gateway, call_limit=12,
                     library_transport=httpx.MockTransport(library_response),
                     identity_policy=IdentityPolicy(registrations_per_client=20), identity_clock=lambda: moment[0],
                     generation_config=configuration(), credential_cipher=cipher, gateway_factory=gateway.factory)
    with TestClient(app) as client:
        account, _ = register(client)
        save = create_save(client).json()["snapshot"]["save_id"]
        yield client, app, gateway, account, save, moment


def action(client, save, identifier="paid-action-001", revision=0):
    return client.post(f"{BASE}/{save}/actions", json={"request_id": identifier, "expected_revision": revision, "action": "本轮仅做离线校验"})


def status(client, save, identifier="paid-action-001"):
    return client.get(f"{BASE}/{save}/actions/{identifier}").json()


def balance(app, account):
    return app.state.generation.credits.balance(account)


def test_invalid_use_rolls_back_inventory_references_turn_and_reserved_credit(paid_runtime, monkeypatch):
    client, app, gateway, account, save, _ = paid_runtime
    snapshot = app.state.repo.save(save)
    credit = balance(app, account)

    def invalid_candidate(state, action, library, history, spend, record=None):
        call_id = spend()
        record(call_id, 1, usage={"input_tokens": 1, "output_tokens": 1})
        library.get("items", "basic-protection-talisman")
        return Candidate(scene_title="事务校验", narrative=["仅用于校验失败回滚。"], change=Change(
            reason="构造没有激活记录的错误结束", items=[Item(
                id="unit-talisman", name="合成成符", category="符箓", status="已用尽", quantity=0,
                description="单元事务检查", definition=DefinitionRef(category="items", key="basic-protection-talisman"),
            )], item_events=[
                ItemEvent(id="acquire", kind="acquire", source="单元检查", reason="事务检查",
                          changes=[ItemTransition(item_id="unit-talisman", before=0, after=1)]),
                ItemEvent(id="spent", kind="consume", source="单元检查", reason="事务检查",
                          changes=[ItemTransition(item_id="unit-talisman", before=1, after=0, status="已用尽")]),
            ], item_use_events=[ItemUseEvent(
                id="invalid-end", use_id="never-activated", item_id="unit-talisman", rule_id="simple-protection",
                kind="end", item_event_id="spent", end_reason="dismissed", reason="校验缺少原使用记录的拒绝",
            )],
        ))

    monkeypatch.setattr(gateway, "generate", invalid_candidate)
    assert action(client, save, "invalid-use-rollback").status_code == 202
    result = status(client, save, "invalid-use-rollback")
    assert result["status"] == "FAILED" and result["error"]["code"] == "ITEM_USE_END_CONFLICT"
    assert app.state.repo.save(save) == snapshot
    after = balance(app, account)
    assert after["spent"] == credit["spent"] and after["available"] == credit["available"]
    with app.state.repo.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM committed_turn WHERE save_id=?", (save,)).fetchone()[0] == 1
        assert connection.execute("SELECT COUNT(*) FROM model_call").fetchone()[0] == 1


def test_ability_identity_conflict_rolls_back_all_state_references_and_reserved_credit(paid_runtime, monkeypatch):
    client, app, gateway, account, save, _ = paid_runtime
    snapshot = app.state.repo.save(save)
    credit = balance(app, account)

    def invalid_candidate(state, action, library, history, spend, record=None):
        call_id = spend()
        record(call_id, 1, usage={"input_tokens": 1, "output_tokens": 1})
        library.get("arts", "basic-breathing")
        return Candidate(scene_title="身份事务校验", narrative=["不作为真实剧情。"], change=Change(
            reason="构造既有本领的错误覆盖", condition="不应提交的状态",
            abilities=[state.abilities[0].model_copy(update={"name": "不应替换的本领名称"})],
        ))

    monkeypatch.setattr(gateway, "generate", invalid_candidate)
    assert action(client, save, "ability-identity-rollback").status_code == 202
    result = status(client, save, "ability-identity-rollback")
    assert result["status"] == "FAILED" and result["error"]["code"] == "ABILITY_IDENTITY_CONFLICT"
    assert app.state.repo.save(save) == snapshot
    after = balance(app, account)
    assert after["spent"] == credit["spent"] and after["available"] == credit["available"]
    assert after["reserved"] == 0
    with app.state.repo.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM committed_turn WHERE save_id=?", (save,)).fetchone()[0] == 1
        assert connection.execute("SELECT COUNT(*) FROM model_call").fetchone()[0] == 1


def test_credentials_encrypted_scoped_not_echoed_and_no_save_validation_call(paid_runtime):
    client, app, gateway, account, save, _ = paid_runtime
    response = client.put("/api/v1/generation/providers/EXAMPLE/credential", json={"model": "GENERAL", "apiKey": KEY})
    assert response.status_code == 200
    assert response.json()["personalProviders"][0]["credentialStatus"] == "SAVED"
    assert KEY not in response.text and "private-model-identity" not in response.text
    assert gateway.calls == 0
    with app.state.repo.connection() as c:
        row = dict(c.execute("SELECT * FROM user_model_credential").fetchone())
        assert KEY.encode() not in row["ciphertext"]
        assert c.execute("SELECT COUNT(*) FROM model_call").fetchone()[0] == 0
    assert app.state.generation.cipher.decrypt(row["ciphertext"], account, "EXAMPLE", row["id"]) == KEY
    with pytest.raises(RuntimeProblem):
        app.state.generation.cipher.decrypt(row["ciphertext"], "other-account", "EXAMPLE", row["id"])
    assert action(client, save).status_code == 202
    assert status(client, save)["status"] == "COMMITTED"
    assert balance(app, account)["spent"] == 0 and gateway.calls == 1
    assert client.get("/api/v1/generation/settings").json()["personalProviders"][0]["credentialStatus"] == "ACTIVE"
    register(client, "second@example.test")
    assert not client.get("/api/v1/generation/settings").json()["personalProviders"][0]["credentialConfigured"]
    assert client.get("/api/v1/account/credits").json()["ledger"] == []


def test_missing_cipher_fails_locally_without_breaking_account_or_creating_preference(paid_runtime):
    client, app, gateway, account, _, _ = paid_runtime
    app.state.generation.cipher = CredentialCipher(None)
    response = client.put("/api/v1/generation/providers/EXAMPLE/credential", json={"model": "GENERAL", "apiKey": KEY})
    assert response.status_code == 503 and KEY not in response.text
    assert client.get("/api/v1/auth/me").json()["authenticated"]
    assert app.state.generation.repo.preference(account) is None and gateway.calls == 0


def test_cipher_tamper_and_wrong_master_reject_before_model_call(paid_runtime):
    client, app, gateway, account, save, _ = paid_runtime
    client.put("/api/v1/generation/providers/EXAMPLE/credential", json={"model": "GENERAL", "apiKey": KEY})
    app.state.generation.cipher = CredentialCipher(secrets.token_bytes(32))
    action(client, save)
    assert status(client, save)["error"]["code"] == "CREDENTIAL_UNREADABLE"
    assert gateway.calls == 0 and balance(app, account)["spent"] == 0
    with app.state.repo.connection(write=True) as c:
        c.execute("UPDATE user_model_credential SET ciphertext=x'010203'")
    action(client, save, "tampered-action")
    assert status(client, save, "tampered-action")["error"]["code"] == "CREDENTIAL_UNREADABLE"


def test_platform_success_atomic_charge_and_duplicate_does_not_charge(paid_runtime):
    client, app, gateway, account, save, _ = paid_runtime
    assert balance(app, account) == {"granted": 5, "spent": 0, "reserved": 0, "available": 5, "expired": 0}
    action(client, save)
    assert status(client, save)["status"] == "COMMITTED"
    assert balance(app, account)["spent"] == 4 and balance(app, account)["available"] == 1
    action(client, save)
    assert gateway.calls == 1 and balance(app, account)["spent"] == 4
    assert client.get(f"{BASE}/{save}").json()["revision"] == 1
    with app.state.repo.connection() as c:
        assert c.execute("SELECT COUNT(*) FROM credit_ledger WHERE kind='SPEND'").fetchone()[0] == 1
    denied = action(client, save, "next-action-001", 1)
    assert denied.status_code == 409 and denied.json()["code"] == "TURN_LIMIT"
    assert status(client, save, "next-action-001")["status"] == "UNKNOWN"
    assert gateway.calls == 1


def test_failed_request_releases_and_retry_uses_frozen_price_route(paid_runtime):
    client, app, gateway, account, save, _ = paid_runtime
    gateway.fail = True
    action(client, save)
    assert status(client, save)["status"] == "FAILED"
    assert balance(app, account)["available"] == 5 and balance(app, account)["reserved"] == 0
    app.state.generation.config = configuration(grant=100, cost=1)
    gateway.fail = False
    action(client, save)
    assert status(client, save)["status"] == "COMMITTED"
    assert balance(app, account)["spent"] == 4 and balance(app, account)["granted"] == 5
    assert gateway.routes[0]["config_hash"] == gateway.routes[1]["config_hash"]
    assert gateway.calls == 2


def test_turn_insert_failure_rolls_back_charge_and_release_is_once(paid_runtime):
    client, app, _, account, save, _ = paid_runtime
    with app.state.repo.connection(write=True) as c:
        c.execute("CREATE TRIGGER reject_paid_turn BEFORE INSERT ON committed_turn WHEN NEW.turn>0 BEGIN SELECT RAISE(ABORT,'rollback'); END")
    action(client, save)
    assert status(client, save)["status"] == "FAILED"
    assert app.state.repo.save(save)["revision"] == 0 and balance(app, account)["available"] == 5
    with app.state.repo.connection(write=True) as c:
        assert c.execute("SELECT COUNT(*) FROM credit_ledger WHERE kind='SPEND'").fetchone()[0] == 0
        row = dict(c.execute("SELECT * FROM action_request").fetchone())
        c.execute("DROP TRIGGER reject_paid_turn")
    app.state.repo.fail(row, "REPEATED", "重复失败")
    assert balance(app, account)["available"] == 5
    action(client, save)
    assert status(client, save)["status"] == "COMMITTED" and balance(app, account)["spent"] == 4


def test_credit_settlement_failure_rolls_back_story_and_releases(paid_runtime):
    client, app, _, account, save, _ = paid_runtime
    with app.state.repo.connection(write=True) as c:
        c.execute("CREATE TRIGGER reject_spend BEFORE INSERT ON credit_ledger WHEN NEW.kind='SPEND' BEGIN SELECT RAISE(ABORT,'credit rollback'); END")
    action(client, save)
    assert status(client, save)["status"] == "FAILED"
    assert app.state.repo.save(save)["revision"] == 0
    assert balance(app, account)["available"] == 5 and balance(app, account)["spent"] == 0


def test_expiring_batches_allocate_first_and_expired_points_unavailable(paid_runtime):
    client, app, _, account, save, moment = paid_runtime
    with app.state.repo.connection(write=True) as c:
        early = app.state.generation.credits.grant(c, account, "synthetic-early", 3, moment[0]+10)
    action(client, save)
    with app.state.repo.connection() as c:
        assert c.execute("SELECT spent FROM credit_grant WHERE id=?", (early,)).fetchone()[0] == 3
    assert balance(app, account)["available"] == 4
    with app.state.repo.connection(write=True) as c:
        app.state.generation.credits.grant(c, account, "synthetic-expiring", 2, moment[0]+5)
    moment[0] += 5
    assert balance(app, account)["available"] == 4 and balance(app, account)["expired"] == 2
    action(client, save, "second-action-001", 1)
    assert balance(app, account)["available"] == 0
    assert action(client, save, "third-action-001", 2).json()["code"] == "TURN_LIMIT"


def test_concurrent_accounts_saves_cannot_overreserve(paid_runtime):
    client, app, _, account, first, _ = paid_runtime
    second = create_save(client, "second-opening").json()["snapshot"]["save_id"]

    def reserve(save):
        try:
            return app.state.repo.reserve(save, ActionInput(request_id="parallel-001", expected_revision=0, action="检查"), account)[1]
        except RuntimeProblem as error:
            return error.code

    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(reserve, [first, second]))
    assert sorted(str(row) for row in outcomes) == ["ACTION_IN_PROGRESS", "True"]
    assert balance(app, account)["reserved"] == 4 and balance(app, account)["available"] == 1
    app.state.repo.recover_interrupted()
    assert balance(app, account)["reserved"] == 0 and balance(app, account)["available"] == 5
    app.state.repo.recover_interrupted()
    assert balance(app, account)["available"] == 5


def test_interrupted_retry_old_worker_cannot_settle_new_reservation(paid_runtime):
    client, app, _, account, save, _ = paid_runtime
    body = ActionInput(request_id="interrupted-001", expected_revision=0, action="检查恢复")
    old, _ = app.state.repo.reserve(save, body, account)
    app.state.repo.recover_interrupted()
    fresh, _ = app.state.repo.reserve(save, body, account)
    app.state.service.execute(old)
    assert balance(app, account)["reserved"] == 4 and balance(app, account)["spent"] == 0
    app.state.service.execute(fresh)
    assert status(client, save, body.request_id)["status"] == "COMMITTED"
    assert balance(app, account)["spent"] == 4 and balance(app, account)["reserved"] == 0


def test_credential_replacement_invalidates_frozen_request_without_fallback(paid_runtime):
    client, app, gateway, account, save, _ = paid_runtime
    url = "/api/v1/generation/providers/EXAMPLE/credential"
    client.put(url, json={"model": "GENERAL", "apiKey": KEY})
    body = ActionInput(request_id="old-credential-001", expected_revision=0, action="检查密钥恢复")
    old, _ = app.state.repo.reserve(save, body, account)
    client.put(url, json={"model": "GENERAL", "apiKey": KEY+"-replacement"})
    app.state.service.execute(old)
    assert status(client, save, body.request_id)["error"]["code"] == "CREDENTIAL_CHANGED"
    with pytest.raises(RuntimeProblem) as error:
        app.state.repo.reserve(save, body, account)
    assert error.value.code == "CREDENTIAL_CHANGED" and gateway.calls == 0
    client.delete(url)
    assert client.get("/api/v1/generation/settings").json()["mode"] == "PERSONAL"
    assert action(client, save).json()["code"] == "CREDENTIAL_REQUIRED"
    assert balance(app, account)["spent"] == 0
    with app.state.repo.connection() as c:
        assert not c.execute("SELECT 1 FROM user_model_credential WHERE ciphertext IS NOT NULL").fetchone()


def test_model_budget_failure_releases_player_points_without_call(paid_runtime):
    client, app, gateway, account, save, _ = paid_runtime
    app.state.service.call_limit = 0
    action(client, save)
    assert status(client, save)["error"]["code"] == "MODEL_BUDGET"
    assert gateway.calls == 0 and balance(app, account)["available"] == 5


def test_untrusted_model_selection_and_errors_do_not_echo_secret(paid_runtime):
    client, app, gateway, _, _, _ = paid_runtime
    response = client.put("/api/v1/generation/providers/EXAMPLE/credential", json={"model": "GENERAL", "apiKey": KEY, "base_url": "http://127.0.0.1:1"})
    assert response.status_code == 422 and KEY not in response.text
    response = client.put("/api/v1/generation/settings", json={"mode": "PERSONAL", "provider": "EXAMPLE", "model": "unconfigured"})
    assert response.status_code == 409 and gateway.calls == 0
    assert "private-model-identity" not in json.dumps(client.get("/api/v1/generation/settings").json())
    assert not any(route.path.startswith("/api/admin") for route in app.routes)


def test_invalid_server_credential_config_does_not_block_login(tmp_path, monkeypatch, caplog):
    path = tmp_path / "unsafe-key.txt"
    path.write_text("sensitive-sentinel")
    path.chmod(0o644)
    monkeypatch.setenv("WENXIAN_RUNTIME_CREDENTIAL_KEY_FILE", str(path))
    monkeypatch.setenv("WENXIAN_RUNTIME_GENERATION_CONFIG", str(path))
    app = create_app(tmp_path / "unavailable.sqlite3")
    with TestClient(app) as client:
        register(client)
        assert client.get("/api/v1/auth/me").json()["authenticated"]
        assert client.get("/api/v1/generation/settings").status_code == 503
        assert create_save(client).status_code == 503
        assert client.get(BASE).json() == []
    assert "sensitive-sentinel" not in caplog.text
