"""本地单进程 API；测试会话独立，处理器不直接写权威状态。"""

import fcntl
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import BackgroundTasks, Depends, FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from starlette.middleware.trustedhost import TrustedHostMiddleware

from .account_save_service import AccountSaveService
from .announcement_api import install_announcement_api
from .announcement_domain import read_announcement_config
from .announcement_service import AnnouncementService
from .creation_domain import SuggestionId, SuggestionInput
from .creation_service import CreationService
from .credential_cipher import CredentialCipher
from .domain import ActionInput, Category, CharacterCreation, Key, RuntimeProblem
from .gateway import configured_gateway
from .generation_api import install_generation_api
from .generation_domain import read_generation_config
from .generation_service import GenerationService
from .identity_api import install_identity_api
from .identity_domain import IdentityPolicy
from .identity_service import IdentityService
from .reference_service import ReferenceService
from .repository import ROOT, Repository
from .service import ActionService
from .session_service import SessionService
from .world_service import WorldSettingService


def create_app(db_path=None, gateway=None, library_url=None, library_transport=None, call_limit=None,
               identity_policy=None, identity_clock=None, generation_config=None, credential_cipher=None,
               gateway_factory=None, announcement_config=None, world_profile_path=None, suggestion_gateway=None):
    configured_limit = os.getenv("WENXIAN_MODEL_CALL_LIMIT", "0") if call_limit is None else call_limit
    if configured_limit == "unlimited":
        configured_limit = None
    else:
        configured_limit = int(configured_limit)
        if configured_limit < 0:
            raise ValueError("调用上限必须为零、正整数或显式 unlimited")
    policy = identity_policy or IdentityPolicy(
        trial_enabled=os.getenv("WENXIAN_RUNTIME_PROFILE", "trial") == "trial",
        secure_cookie=os.getenv("WENXIAN_RUNTIME_SECURE_COOKIE", "false").lower() == "true",
        allowed_origins=tuple(value.strip().rstrip("/") for value in os.getenv("WENXIAN_RUNTIME_ORIGINS", "").split(",") if value.strip()),
    )
    default_database = "var/game.sqlite3" if policy.trial_enabled else "var/account.sqlite3"
    path = Path(db_path or os.getenv("WENXIAN_RUNTIME_DB", str(ROOT / default_database))).resolve()
    repo = Repository(path)
    worlds = WorldSettingService(repo, library_url or os.getenv("WENXIAN_LIBRARY_URL", "http://127.0.0.1:8011"),
                                 library_transport, world_profile_path or os.getenv("WENXIAN_RUNTIME_WORLD_PROFILE"))
    service = ActionService(
        repo,
        gateway or configured_gateway(),
        library_url or os.getenv("WENXIAN_LIBRARY_URL", "http://127.0.0.1:8011"),
        configured_limit,
        library_transport,
        worlds,
    )

    @asynccontextmanager
    async def lifespan(app):
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.with_suffix(".lockfile").open("a") as process_lock:
            # 防止另一服务启动时把仍在生成的请求误标为中断。
            fcntl.flock(process_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            repo.migrate()
            path.chmod(0o600)
            repo.recover_interrupted()
            yield
            fcntl.flock(process_lock, fcntl.LOCK_UN)

    app = FastAPI(title="问仙录本地游戏运行时", lifespan=lifespan)
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=["localhost", "127.0.0.1", "[::1]", "testserver"])
    app.state.repo, app.state.service = repo, service
    bearer = HTTPBearer(auto_error=False)
    app.state.worlds = worlds
    sessions = SessionService(repo, worlds)
    references = ReferenceService(repo, service.library_url, library_transport, worlds)
    identity = IdentityService(repo, policy, identity_clock)
    account_saves = AccountSaveService(repo, service, policy, worlds)
    app.state.identity, app.state.account_saves = identity, account_saves
    install_identity_api(app, identity, account_saves, references)
    generation = GenerationService(repo, generation_config or read_generation_config(),
                                   credential_cipher or CredentialCipher.configured(), service.gateway,
                                   identity_clock, gateway_factory)
    repo.generation = generation
    app.state.generation = generation
    install_generation_api(app, generation)
    suggestions = CreationService(repo, worlds, generation, configured_limit, suggestion_gateway)
    app.state.suggestions = suggestions

    @app.post("/api/runtime/v1/account/creation/suggestions", status_code=202)
    def create_suggestions(body: SuggestionInput, tasks: BackgroundTasks, account=Depends(app.state.player_dependency)):
        row, newly_reserved = suggestions.reserve(account["id"], body)
        if newly_reserved:
            tasks.add_task(suggestions.execute, account["id"], body.request_id)
        return suggestions.repo.view(row)

    @app.get("/api/runtime/v1/account/creation/suggestions/{request_id}")
    def read_suggestions(request_id: SuggestionId, account=Depends(app.state.player_dependency)):
        return suggestions.status(account["id"], request_id)
    announcements = AnnouncementService(repo, announcement_config or read_announcement_config(), identity_clock)
    app.state.announcements = announcements
    install_announcement_api(app, announcements)

    def problem_response(code, message, status):
        # 原 H5/管理台使用顶层错误；试玩入口读取嵌套错误。两者都不返回输入值。
        return JSONResponse(status_code=status, content={"error": {"code": code, "message": message},
                                                        "code": code, "message": message})

    @app.middleware("http")
    async def local_request_boundary(request: Request, call_next):
        if request.method not in {"GET", "HEAD", "OPTIONS"}:
            origin = request.headers.get("origin")
            same_origin = f"{request.url.scheme}://{request.url.netloc}"
            if request.headers.get("sec-fetch-site") == "cross-site" or (origin and origin not in {same_origin, *policy.allowed_origins}):
                return problem_response("ORIGIN_REJECTED", "请从当前游戏页面重新操作。", 403)
            has_body = request.headers.get("content-length", "0") != "0" or "transfer-encoding" in request.headers
            if has_body and request.headers.get("content-type", "").split(";")[0].strip().lower() != "application/json":
                return problem_response("INVALID_CONTENT_TYPE", "请求格式不正确。", 415)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    def owned_save(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if not policy.trial_enabled:
            raise RuntimeProblem("TRIAL_DISABLED", "请通过账号命卷入口进入。", 404)
        if not credentials:
            raise RuntimeProblem("UNAUTHORIZED", "请先进入本地试玩。", 401)
        return repo.owner_save(credentials.credentials)

    @app.exception_handler(RuntimeProblem)
    async def problem_handler(request: Request, exc: RuntimeProblem):
        return problem_response(exc.code, exc.message, exc.status)

    @app.exception_handler(RequestValidationError)
    async def invalid_input(request: Request, exc: RequestValidationError):
        return problem_response("INVALID_INPUT", "填写内容格式不正确，请检查后重试。", 422)

    @app.get("/api/runtime/v1/health")
    def health():
        return {"status": "ok", "mode": service.gateway.mode, "local_test_only": True,
                "profile": "trial" if policy.trial_enabled else "account"}

    @app.post("/api/runtime/v1/sessions", status_code=201)
    def session(body: CharacterCreation, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if not policy.trial_enabled:
            raise RuntimeProblem("TRIAL_DISABLED", "请通过账号命卷入口开卷。", 404)
        if not credentials:
            raise RuntimeProblem("UNAUTHORIZED", "请先确认创角资料。", 401)
        save_id = sessions.create(body, credentials.credentials)
        return {"snapshot": service.snapshot(save_id)}

    @app.get("/api/runtime/v1/creation")
    def creation():
        return sessions.preview()

    @app.get("/api/runtime/v1/save/summary")
    def summary(save_id: str = Depends(owned_save)):
        return repo.summary(save_id)

    @app.get("/api/runtime/v1/save")
    def save(save_id: str = Depends(owned_save)):
        return service.snapshot(save_id)

    @app.get("/api/runtime/v1/history")
    def history(before_turn: int | None = None, limit: int = 3, save_id: str = Depends(owned_save)):
        return repo.history(save_id, before_turn, limit)

    @app.get("/api/runtime/v1/reference")
    def reference_overview(save_id: str = Depends(owned_save)):
        return references.overview(save_id)

    @app.get("/api/runtime/v1/reference/entries/{category}")
    def reference_entries(category: Category, q: str = Query("", max_length=80),
                          offset: int = Query(0, ge=0, le=1000000), save_id: str = Depends(owned_save)):
        return references.browse(category, q, offset)

    @app.get("/api/runtime/v1/reference/entries/{category}/{key}")
    def reference_detail(category: Category, key: Key, save_id: str = Depends(owned_save)):
        return references.detail(save_id, category, key)

    @app.post("/api/runtime/v1/actions", status_code=202)
    def action(body: ActionInput, tasks: BackgroundTasks, save_id: str = Depends(owned_save)):
        request, newly_reserved = repo.reserve(save_id, body)
        if newly_reserved:
            tasks.add_task(service.execute, request)
        return repo.status(save_id, body.request_id)

    @app.get("/api/runtime/v1/actions/{request_id}")
    def action_status(request_id: str, save_id: str = Depends(owned_save)):
        return repo.status(save_id, request_id)

    return app


app = create_app()
