"""玩家资料查看的权限、分页、版本与只读边界；独立临时库，不调用模型。"""

import secrets

import httpx
from fastapi.testclient import TestClient
from setting_library import setting_response

from wenxian_runtime.api import create_app
from wenxian_runtime.domain import Candidate


class ReferenceLibrary:
    def __init__(self):
        self.calls, self.version, self.offline = [], 2, False

    def entry(self, revision, key="basic-breathing"):
        return {"key": key, "name": "基础吐纳功", "entry_kind": "overview", "revision": revision,
                "content_hash": str(revision) * 64, "description": f"第{revision}版介绍",
                "usage": "调息", "limitations": "不会自动晋阶", "source": "问仙录测试设定",
                "adaptation": "作者维护的改编依据，不属于玩家使用说明。",
                "sources": [{"title": "资料出处", "locator": "第一章", "supports": "用途",
                             "url": "javascript:alert(1)"}]}

    def respond(self, request):
        self.calls.append(request)
        if self.offline:
            return httpx.Response(503)
        setting = setting_response(request)
        if setting is not None:
            return setting
        if "/revisions/" in request.url.path:
            return httpx.Response(200, json={"revision": 2, "content_hash": "2" * 64,
                                             "content": self.entry(2)})
        if request.url.path.endswith("/arts/") or request.url.path.endswith("/items/"):
            offset = int(request.url.params.get("offset", 0))
            rows = [self.entry(self.version, f"item-{i}") for i in range(13)]
            if request.url.params.get("name_contains"):
                rows = rows[:1] if request.url.params["name_contains"] == "吐纳" else []
            return httpx.Response(200, json={"count": len(rows), "results": rows[offset:offset + 12]})
        return httpx.Response(200, json=self.entry(self.version))


class ReferenceGateway:
    mode = "fixture"

    def generate(self, state, action, library, history, spend, record):
        library.get("arts", "basic-breathing")
        return Candidate(scene_title="测试回应", narrative=["资料引用已保存。"])


def setup_reference(tmp_path):
    library = ReferenceLibrary()
    app = create_app(tmp_path / "reference.sqlite3", ReferenceGateway(),
                     library_transport=httpx.MockTransport(library.respond))
    return app, library


def authorize(client):
    client.headers["Authorization"] = f"Bearer {secrets.token_urlsafe(32)}"
    response = client.post("/api/runtime/v1/sessions", json={
        "opening_id": "qingmu-trial", "name": "沈青", "goal": "了解青溪镇",
        "world_setting_hash": client.get("/api/runtime/v1/creation").json()["setting_hash"],
    })
    assert response.status_code == 201
    return response.json()["snapshot"]["save_id"]


def test_reference_catalog_detail_are_read_only_and_paginated(tmp_path):
    app, library = setup_reference(tmp_path)
    with TestClient(app) as client:
        save_id = authorize(client)
        library.calls.clear()
        before, history = app.state.repo.save(save_id), app.state.repo.history(save_id)
        overview = client.get("/api/runtime/v1/reference").json()
        assert overview["world"]["title"] == "青溪镇" and len(overview["categories"]) == 6
        assert not library.calls
        first = client.get("/api/runtime/v1/reference/entries/items").json()
        assert first["count"] == 13 and len(first["results"]) == 12
        second = client.get("/api/runtime/v1/reference/entries/items?offset=12").json()
        assert len(second["results"]) == 1
        assert library.calls[-1].url.params["offset"] == "12"
        filtered = client.get("/api/runtime/v1/reference/entries/arts?q=吐纳").json()
        assert filtered["count"] == 1
        detail = client.get("/api/runtime/v1/reference/entries/arts/basic-breathing").json()
        assert detail["scope"] == "save" and detail["revision"] == 2 and detail["example"]
        assert detail["sources"][0]["url"] == ""
        assert [section["title"] for section in detail["sections"]] == ["介绍", "用途与用法", "限制"]
        assert library.entry(2)["adaptation"] == "作者维护的改编依据，不属于玩家使用说明。"
        assert "content_hash" not in detail and "effects" not in detail
        assert app.state.repo.save(save_id) == before and app.state.repo.history(save_id) == history
        with app.state.repo.connection() as c:
            assert c.execute("SELECT COUNT(*) FROM model_call").fetchone()[0] == 0


def test_reference_detail_uses_saved_revision_without_repinning(tmp_path):
    app, library = setup_reference(tmp_path)
    with TestClient(app) as client:
        save_id = authorize(client)
        client.post("/api/runtime/v1/actions", json={"request_id": "pin-reference-1",
                    "expected_revision": 0, "action": "查看吐纳的来历"})
        before = app.state.repo.save(save_id)
        assert before["revision"] == 1
        library.version = 3
        detail = client.get("/api/runtime/v1/reference/entries/arts/basic-breathing").json()
        assert detail["scope"] == "save" and detail["revision"] == 2
        assert detail["sections"][0]["text"] == "第2版介绍"
        calls = len(library.calls)
        assert client.get("/api/runtime/v1/reference/entries/arts/basic-breathing").json() == detail
        assert len(library.calls) == calls
        assert app.state.repo.save(save_id) == before


def test_reference_auth_validation_and_outage_are_isolated(tmp_path):
    app, library = setup_reference(tmp_path)
    with TestClient(app) as client:
        for path in ("/reference", "/reference/entries/items", "/reference/entries/items/item-1"):
            assert client.get("/api/runtime/v1" + path).status_code == 401
        save_id = authorize(client)
        for path in ("/reference/entries/unknown", "/reference/entries/items?offset=-1",
                     "/reference/entries/items?q=" + "字" * 81):
            assert client.get("/api/runtime/v1" + path).status_code == 422
        before = app.state.repo.save(save_id)
        library.offline = True
        assert client.get("/api/runtime/v1/reference/entries/items").status_code == 503
        assert client.get("/api/runtime/v1/reference/entries/arts/basic-breathing").status_code == 200
        assert client.get("/api/runtime/v1/reference").status_code == 200
        assert client.get("/api/runtime/v1/save").json()["revision"] == 0
        assert app.state.repo.save(save_id) == before
