"""玩家公告 HTTP 入口；公开最新一条，其余读取和回执验证账号。"""

from fastapi import Depends

from .domain import Key


def install_announcement_api(app, service):
    player = app.state.player_dependency

    @app.get("/api/v1/release-notes/public-latest")
    def latest():
        return service.latest()

    @app.get("/api/v1/release-notes")
    def published(account=Depends(player)):
        return service.published(account["id"])

    @app.get("/api/v1/release-notes/pending")
    def pending(account=Depends(player)):
        return service.pending(account["id"])

    @app.post("/api/v1/release-notes/{release_id}/read", status_code=204)
    def mark_read(release_id: Key, account=Depends(player)):
        service.mark_read(account["id"], release_id)
