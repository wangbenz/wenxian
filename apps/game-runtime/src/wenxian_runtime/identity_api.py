"""玩家账号 HTTP 适配；验证会话用途后委托应用服务，不直接修改数据库。"""

from fastapi import BackgroundTasks, Depends, Query, Request, Response

from .domain import ActionInput, Category, Key, RuntimeProblem
from .identity_domain import (
    AccountCreation,
    PlayerLogin,
    PlayerPassword,
    PlayerRegistration,
)

PLAYER_COOKIE = "WENXIAN_RUNTIME_SESSION"


def install_identity_api(app, identity, saves, references):
    def player(request: Request):
        return identity.principal(request.cookies.get(PLAYER_COOKIE), "PLAYER")

    def client(request):
        # 本地代理的转发头不是可信身份，不据此绕过限流。
        return request.client.host if request.client else "unknown"

    def cookie(response, token):
        response.set_cookie(PLAYER_COOKIE, token or "", httponly=True, secure=identity.policy.secure_cookie,
                            samesite="lax", path="/api", max_age=identity.policy.session_seconds if token else 0)

    def view(account, new_user=False):
        return identity.player_view(account, saves.repo.has_save(account["id"]) if account else False, new_user)

    @app.get("/api/v1/auth/access-status")
    def access_status():
        return {"restricted": False, "title": "", "message": "", "resumeAt": ""}

    @app.post("/api/v1/auth/register")
    def register(body: PlayerRegistration, request: Request, response: Response):
        account, token = identity.register(body.email, body.password, client(request))
        cookie(response, token)
        return view(account, True)

    @app.post("/api/v1/auth/login")
    def login(body: PlayerLogin, request: Request, response: Response):
        account, token = identity.login("PLAYER", body.email, body.password, client(request))
        cookie(response, token)
        return view(account)

    @app.get("/api/v1/auth/me")
    def me(request: Request):
        return view(identity.principal(request.cookies.get(PLAYER_COOKIE), "PLAYER", required=False))

    @app.post("/api/v1/auth/logout", status_code=204)
    def logout(request: Request, response: Response):
        identity.logout(request.cookies.get(PLAYER_COOKIE), "PLAYER")
        cookie(response, None)

    @app.post("/api/v1/auth/password")
    def password(body: PlayerPassword, request: Request, response: Response):
        account, token = identity.change_password(request.cookies.get(PLAYER_COOKIE), "PLAYER",
                                                  body.currentPassword, body.newPassword, client(request))
        cookie(response, token)
        return view(account)

    @app.post("/api/v1/auth/email-verifications")
    def email_verification():
        raise RuntimeProblem("EMAIL_NOT_CONFIGURED", "本地服务尚未启用邮箱验证。", 503)

    @app.delete("/api/v1/auth/account")
    def delete_account(account=Depends(player)):
        raise RuntimeProblem("ACCOUNT_DELETION_DISABLED", "本地验收暂不开放账号注销。", 409)

    @app.get("/api/runtime/v1/account/saves")
    def list_saves(account=Depends(player)):
        return saves.summaries(account)

    @app.get("/api/runtime/v1/account/config")
    def account_config(account=Depends(player)):
        return {"maximum_saves": saves.policy.maximum_saves}

    @app.post("/api/runtime/v1/account/saves", status_code=201)
    def create_save(body: AccountCreation, account=Depends(player)):
        return {"snapshot": saves.create(account, body)}

    @app.get("/api/runtime/v1/account/saves/{save_id}")
    def read_save(save_id: str, account=Depends(player)):
        return saves.snapshot(account, save_id)

    @app.get("/api/runtime/v1/account/saves/{save_id}/history")
    def history(save_id: str, before_turn: int | None = None, limit: int = Query(3, ge=1, le=20), account=Depends(player)):
        return saves.actions.repo.history(saves.owned(account, save_id), before_turn, limit)

    @app.post("/api/runtime/v1/account/saves/{save_id}/actions", status_code=202)
    def action(save_id: str, body: ActionInput, tasks: BackgroundTasks, account=Depends(player)):
        request, newly_reserved = saves.reserve(account, save_id, body)
        if newly_reserved:
            tasks.add_task(saves.actions.execute, request)
        return saves.actions.repo.status(save_id, body.request_id)

    @app.get("/api/runtime/v1/account/saves/{save_id}/actions/{request_id}")
    def action_status(save_id: str, request_id: str, account=Depends(player)):
        return saves.actions.repo.status(saves.owned(account, save_id), request_id)

    @app.get("/api/runtime/v1/account/saves/{save_id}/reference")
    def overview(save_id: str, account=Depends(player)):
        saves.owned(account, save_id)
        return references.overview(save_id)

    @app.get("/api/runtime/v1/account/saves/{save_id}/reference/entries/{category}")
    def entries(save_id: str, category: Category, q: str = Query("", max_length=80),
                offset: int = Query(0, ge=0, le=1000000), account=Depends(player)):
        saves.owned(account, save_id)
        return references.browse(category, q, offset)

    @app.get("/api/runtime/v1/account/saves/{save_id}/reference/entries/{category}/{key}")
    def detail(save_id: str, category: Category, key: Key, account=Depends(player)):
        return references.detail(saves.owned(account, save_id), category, key)

    app.state.player_dependency = player
