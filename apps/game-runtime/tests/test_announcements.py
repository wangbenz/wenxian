"""公告隔离与回执恢复；只用显式合成配置，不读取或发布真实公告。"""

from fastapi.testclient import TestClient
from test_identity import BASE, register

from wenxian_runtime.announcement_domain import AnnouncementConfig
from wenxian_runtime.api import create_app
from wenxian_runtime.identity_domain import IdentityPolicy


def test_receipts_scoped_idempotent_and_changed_content_becomes_unread(tmp_path):
    config = AnnouncementConfig.model_validate({"announcements": [
        {"id": "local-note", "version": "local", "title": "联调公告", "content": "仅用于隔离验证", "publishedAt": "2020-01-01T00:00:00Z"},
        {"id": "future-note", "version": "future", "title": "未发布", "content": "不得提前展示", "publishedAt": "2099-01-01T00:00:00Z"},
    ]})
    app = create_app(tmp_path / "notes.sqlite3", announcement_config=config,
                     identity_policy=IdentityPolicy(registrations_per_client=20))
    with TestClient(app) as client:
        assert client.get("/api/v1/release-notes/public-latest").json()["id"] == "local-note"
        assert client.get("/api/v1/release-notes").status_code == 401
        register(client)
        token = client.cookies.get("WENXIAN_RUNTIME_SESSION")
        assert len(client.get("/api/v1/release-notes").json()) == 1
        for _ in range(2):
            assert client.post("/api/v1/release-notes/local-note/read").status_code == 204
        assert client.get("/api/v1/release-notes/pending").json() is None
        assert client.post("/api/v1/release-notes/future-note/read").status_code == 404
        with app.state.repo.connection() as c:
            assert c.execute("SELECT COUNT(*) FROM announcement_receipt").fetchone()[0] == 1
        register(client, "second-note@example.test")
        assert client.get("/api/v1/release-notes/pending").json()["unread"]
    with TestClient(app) as restarted:
        restarted.cookies.set("WENXIAN_RUNTIME_SESSION", token)
        assert restarted.get("/api/v1/release-notes/pending").json() is None
        config.announcements[0].content = "更新后的合成内容"
        assert restarted.get("/api/v1/release-notes/pending").json()["unread"]


def test_unconfigured_or_broken_announcements_do_not_block_account(tmp_path, monkeypatch):
    broken = tmp_path / "bad-notes.json"
    broken.write_text("invalid")
    monkeypatch.setenv("WENXIAN_RUNTIME_ANNOUNCEMENTS", str(broken))
    app = create_app(tmp_path / "broken.sqlite3")
    with TestClient(app) as client:
        assert client.get("/api/v1/release-notes/public-latest").status_code == 503
        register(client)
        assert client.get(BASE).json() == []
        app.state.announcements.config = AnnouncementConfig()
        assert client.get("/api/v1/release-notes").json() == []
        assert client.get("/api/v1/release-notes/pending").json() is None
