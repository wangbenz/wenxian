"""隔离账号验证：跨身份权限、密码与会话竞态、限流、原子开卷和旧试玩兼容，无真实模型。"""

import secrets
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import httpx
import pytest
from fastapi.testclient import TestClient
from test_runtime import TestGateway, library_response, new_session

from wenxian_runtime.api import create_app
from wenxian_runtime.domain import ActionInput, RuntimeProblem
from wenxian_runtime.identity_api import PLAYER_COOKIE
from wenxian_runtime.identity_domain import AccountCreation, IdentityPolicy, token_hash

PASSWORD = "isolated-password-123"
PROFILE = {"opening_id": "qingmu-trial", "name": "林默", "goal": "问清条件"}
BASE = "/api/runtime/v1/account/saves"


@pytest.fixture
def account_runtime(tmp_path):
    moment = [1800000000]
    policy = IdentityPolicy(registrations_per_client=20)
    gateway = TestGateway()
    app = create_app(tmp_path / "accounts.sqlite3", gateway,
                     library_transport=httpx.MockTransport(library_response),
                     identity_policy=policy, identity_clock=lambda: moment[0])
    with TestClient(app) as client:
        yield client, app, moment, gateway


def register(client, email="first@example.test"):
    response = client.post("/api/v1/auth/register", json={"email": email, "password": PASSWORD})
    assert response.status_code == 200, response.text
    return response.json()["user"]["userId"], client.cookies.get(PLAYER_COOKIE)


def create_save(client, request_id="opening-001", **profile):
    setting_hash = client.get("/api/runtime/v1/creation").json()["setting_hash"]
    return client.post(BASE, json={"request_id": request_id,
                       "character": {**PROFILE, "world_setting_hash": setting_hash, **profile}})


def test_registration_login_logout_and_no_plain_secrets(account_runtime):
    client, app, _, _ = account_runtime
    account, token = register(client, "FIRST@example.test")
    assert client.get("/api/v1/auth/me").json()["authenticated"]
    assert client.cookies.get("WENXIAN_SESSION") is None
    assert client.post("/api/v1/auth/register", json={"email": "first@example.test", "password": PASSWORD}).status_code == 409
    with app.state.repo.connection() as c:
        row = c.execute("SELECT * FROM identity_account WHERE id=?", (account,)).fetchone()
        assert row["login"] == "first@example.test"
        assert PASSWORD not in row["password_hash"]
        stored = c.execute("SELECT token_hash FROM identity_session").fetchone()[0]
        assert stored == token_hash(token) and stored != token
    response = client.post("/api/v1/auth/logout")
    assert response.status_code == 204 and not client.get("/api/v1/auth/me").json()["authenticated"]
    assert app.state.identity.principal(token, "PLAYER", required=False) is None
    response = client.post("/api/v1/auth/login", json={"email": "first@example.test", "password": PASSWORD})
    assert response.status_code == 200
    header = response.headers["set-cookie"]
    assert "HttpOnly" in header and "SameSite=lax" in header and "Path=/api" in header
    assert response.headers["cache-control"] == "no-store"
    assert PASSWORD not in response.text and token not in response.text


def test_password_change_revokes_all_old_sessions_and_rotates_current(account_runtime):
    client, app, _, _ = account_runtime
    _, token = register(client)
    _, second = app.state.identity.login("PLAYER", "first@example.test", PASSWORD, "other-client")
    bad = client.post("/api/v1/auth/password", json={"currentPassword": "wrong", "newPassword": "new-password-123"})
    assert bad.status_code == 400
    response = client.post("/api/v1/auth/password", json={"currentPassword": PASSWORD, "newPassword": "new-password-123"})
    assert response.status_code == 200 and client.cookies.get(PLAYER_COOKIE) != token
    for old in (token, second):
        assert app.state.identity.principal(old, "PLAYER", required=False) is None
    assert app.state.identity.principal(client.cookies.get(PLAYER_COOKIE), "PLAYER")
    assert client.post("/api/v1/auth/login", json={"email": "first@example.test", "password": PASSWORD}).status_code == 401
    assert client.post("/api/v1/auth/login", json={"email": "first@example.test", "password": "new-password-123"}).status_code == 200


def test_stale_password_verification_cannot_issue_session(account_runtime):
    client, app, _, _ = account_runtime
    _, token = register(client)
    identity = app.state.identity
    old, attempt = identity.repo.reserve_login("PLAYER", "first@example.test", "s", "c", int(identity.clock()))
    client.post("/api/v1/auth/password", json={"currentPassword": PASSWORD, "newPassword": "new-password-123"})
    with pytest.raises(RuntimeProblem, match="重新登录"):
        identity.repo.finish_login(old, attempt, token_hash(secrets.token_urlsafe(32)), int(identity.clock()))
    assert identity.principal(token, "PLAYER", required=False) is None


def test_absolute_and_idle_session_expiry(account_runtime):
    client, app, moment, _ = account_runtime
    _, token = register(client)
    moment[0] += app.state.identity.policy.idle_seconds
    assert app.state.identity.principal(token, "PLAYER", required=False) is None
    _, token = app.state.identity.login("PLAYER", "first@example.test", PASSWORD, "second-client")
    with app.state.repo.connection(write=True) as c:
        c.execute("UPDATE identity_session SET expires_at=? WHERE token_hash=?", (moment[0], token_hash(token)))
    assert app.state.identity.principal(token, "PLAYER", required=False) is None


def test_login_failures_are_durable_and_limit_concurrent_attempts(account_runtime):
    client, app, moment, _ = account_runtime
    register(client)

    def fail(_):
        try:
            app.state.identity.login("PLAYER", "first@example.test", "incorrect", "same-ip")
        except RuntimeProblem as error:
            return error.status

    with ThreadPoolExecutor(max_workers=8) as pool:
        outcomes = list(pool.map(fail, range(8)))
    assert sorted(outcomes) == [401]*5 + [429]*3
    from wenxian_runtime.identity_service import IdentityService
    reopened = IdentityService(app.state.repo, app.state.identity.policy, lambda: moment[0])
    with pytest.raises(RuntimeProblem) as error:
        reopened.login("PLAYER", "first@example.test", PASSWORD, "changed-ip")
    assert error.value.status == 429
    moment[0] += 601
    assert reopened.login("PLAYER", "first@example.test", PASSWORD, "same-ip")[0]["kind"] == "PLAYER"


def test_cross_origin_and_validation_never_echo_secrets(account_runtime):
    client, _, _, _ = account_runtime
    for headers in ({"Origin": "https://untrusted.example"}, {"Sec-Fetch-Site": "cross-site"}):
        assert client.post("/api/v1/auth/register", headers=headers, json={"email": "first@example.test", "password": PASSWORD}).status_code == 403
    assert client.post("/api/v1/auth/login", content="email=a&password=secret", headers={"Content-Type": "text/plain"}).status_code == 415
    response = client.post("/api/v1/auth/register", json={"email": "invalid", "password": "sentinel-secret", "role": "OWNER"})
    assert response.status_code == 422 and "sentinel-secret" not in response.text and "invalid" not in response.text
    assert client.get("/api/v1/auth/me", headers={"Host": "attacker.example"}).status_code == 400


def test_save_ownership_and_every_account_route_is_scoped(account_runtime):
    client, app, _, gateway = account_runtime
    register(client)
    save_id = create_save(client).json()["snapshot"]["save_id"]
    assert client.get("/api/v1/auth/me").json()["hasSave"]
    register(client, "second@example.test")
    assert client.get(BASE).json() == []
    for suffix in ("", "/history", "/actions/action-001", "/reference", "/reference/entries/items", "/reference/entries/items/anything"):
        assert client.get(f"{BASE}/{save_id}{suffix}").status_code == 404
    assert client.post(f"{BASE}/{save_id}/actions", json={"request_id": "action-001", "expected_revision": 0, "action": "查看"}).status_code == 404
    assert gateway.calls == 0 and app.state.repo.save(save_id)["revision"] == 0


def test_opening_is_idempotent_conflicts_rejected_and_legacy_unchanged(account_runtime):
    client, app, _, _ = account_runtime
    old = new_session(client)
    original = app.state.repo.save(old["snapshot"]["save_id"])
    register(client)
    first = create_save(client).json()["snapshot"]
    assert create_save(client).json()["snapshot"]["save_id"] == first["save_id"]
    assert create_save(client, goal="改变目标").status_code == 409
    assert len(client.get(BASE).json()) == 1
    assert app.state.repo.save(old["snapshot"]["save_id"]) == original
    assert app.state.repo.owner_save(old["token"]) == old["snapshot"]["save_id"]


def test_concurrent_creation_limit_and_atomic_rollback(account_runtime):
    client, app, _, _ = account_runtime
    _, token = register(client)
    account = app.state.identity.principal(token, "PLAYER")
    body = AccountCreation(request_id="opening-concurrent",
                           character={**PROFILE, "world_setting_hash": app.state.worlds.preview()["setting_hash"]})
    with ThreadPoolExecutor(max_workers=2) as pool:
        ids = list(pool.map(lambda _: app.state.account_saves.create(account, body)["save_id"], range(2)))
    assert ids[0] == ids[1]
    app.state.account_saves.policy = replace(app.state.account_saves.policy, maximum_saves=1)
    assert create_save(client, request_id="another-opening").status_code == 409
    register(client, "second@example.test")
    second = app.state.identity.principal(client.cookies.get(PLAYER_COOKIE), "PLAYER")
    with app.state.repo.connection(write=True) as c:
        c.execute("CREATE TRIGGER reject_account_save BEFORE INSERT ON account_save BEGIN SELECT RAISE(ABORT,'rollback'); END")
        counts = [c.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in ("runtime_session", "runtime_save", "committed_turn")]
    with pytest.raises(sqlite3.IntegrityError):
        app.state.account_saves.create(second, body)
    with app.state.repo.connection() as c:
        assert counts == [c.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in ("runtime_session", "runtime_save", "committed_turn")]


def test_corrupt_save_does_not_block_login_list_or_another_save(account_runtime):
    client, app, _, _ = account_runtime
    register(client)
    first = create_save(client).json()["snapshot"]["save_id"]
    second = create_save(client, request_id="opening-002").json()["snapshot"]["save_id"]
    with app.state.repo.connection(write=True) as c:
        c.execute("UPDATE runtime_save SET state_json='broken' WHERE id=?", (first,))
    assert client.get("/api/v1/auth/me").status_code == 200
    assert len(client.get(BASE).json()) == 2
    assert client.get(f"{BASE}/{first}").status_code == 409
    assert client.get(f"{BASE}/{second}").status_code == 200


def test_account_actions_reuse_transaction_and_account_concurrency(account_runtime):
    client, app, _, gateway = account_runtime
    account_id, _ = register(client)
    first = create_save(client).json()["snapshot"]["save_id"]
    second = create_save(client, request_id="opening-002").json()["snapshot"]["save_id"]
    action = {"request_id": "action-001", "expected_revision": 0, "action": "先问清条件"}
    assert client.post(f"{BASE}/{first}/actions", json=action).status_code == 202
    assert client.get(f"{BASE}/{first}/actions/action-001").json()["status"] == "COMMITTED"
    assert client.post(f"{BASE}/{first}/actions", json=action).status_code == 202 and gateway.calls == 1
    with pytest.raises(RuntimeProblem):
        app.state.repo.reserve(first, ActionInput(**{**action, "request_id": "bypass-001", "expected_revision": 1}))
    app.state.repo.reserve(first, ActionInput(**{**action, "request_id": "pending-001", "expected_revision": 1}), account_id)
    response = client.post(f"{BASE}/{second}/actions", json=action)
    assert response.status_code == 409 and response.json()["code"] == "ACTION_IN_PROGRESS"


def test_account_profile_disables_old_bearer_creation(tmp_path):
    app = create_app(tmp_path / "closed.sqlite3", identity_policy=IdentityPolicy(trial_enabled=False))
    with TestClient(app) as client:
        assert client.post("/api/runtime/v1/sessions", json=PROFILE, headers={"Authorization": f"Bearer {secrets.token_urlsafe(32)}"}).status_code == 404
        assert client.get("/api/runtime/v1/save").status_code == 404
        assert client.get("/api/runtime/v1/health").json()["profile"] == "account"
