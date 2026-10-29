"""玩家推演设置与额度只读 HTTP 入口；不提供管理配置或额外模型验证调用。"""

from fastapi import Depends, Query

from .generation_domain import Code, CredentialInput, GenerationPreference


def install_generation_api(app, service):
    player = app.state.player_dependency

    @app.get("/api/v1/generation/settings")
    def settings(account=Depends(player)):
        return service.settings(account["id"])

    @app.put("/api/v1/generation/settings")
    def save_settings(body: GenerationPreference, account=Depends(player)):
        return service.save_settings(account["id"], body)

    @app.put("/api/v1/generation/providers/{provider}/credential")
    def save_credential(provider: Code, body: CredentialInput, account=Depends(player)):
        return service.save_credential(account["id"], provider, body)

    @app.delete("/api/v1/generation/providers/{provider}/credential")
    def delete_credential(provider: Code, account=Depends(player)):
        return service.delete_credential(account["id"], provider)

    @app.get("/api/v1/account/credits")
    def credits(before: int | None = Query(None, ge=1), limit: int = Query(50, ge=1, le=100), account=Depends(player)):
        return {"balance": service.credits.balance(account["id"]), "ledger": service.credits.ledger(account["id"], before, limit)}
