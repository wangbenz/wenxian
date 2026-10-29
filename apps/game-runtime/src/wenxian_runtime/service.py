"""行动应用服务：读取所需事实、模型建议、领域归并和一次提交；失败不推进本局。"""

import hashlib
import json
import logging

from pydantic import ValidationError

from .domain import (
    Candidate,
    GameState,
    RuntimeProblem,
    apply_candidate,
    item_use_constraints,
    validate_item_uses,
)
from .library import LibraryReader


class ActionService:
    def __init__(self, repository, gateway, library_url, call_limit=0, library_transport=None, worlds=None):
        self.repo, self.gateway, self.library_url = repository, gateway, library_url
        self.call_limit, self.library_transport = call_limit, library_transport
        self.worlds = worlds

    def snapshot(self, save_id):
        snapshot = self.repo.save(save_id)
        snapshot["mode"] = self.gateway.mode
        snapshot["history"] = self.repo.history(save_id, limit=20)
        snapshot["active_action"] = self.repo.active_request(save_id)
        return snapshot

    def execute(self, request):
        library = None
        try:
            self.repo.ensure_active(request)
            gateway = self.repo.generation.gateway_for(request, self.gateway) if self.repo.generation else self.gateway
            if gateway.mode == "langchain" and self.repo.has_simulated_history(request["save_id"]):
                raise RuntimeProblem(
                    "SIMULATED_SAVE", "这份命卷含模拟记录，请新建试玩命卷后进行真实续写。", 409
                )
            snapshot = self.repo.save(request["save_id"])
            if snapshot["revision"] != request["expected_revision"]:
                raise RuntimeProblem("REVISION_CONFLICT", "命卷已有新结果，请先确认。")
            state = GameState.model_validate(snapshot["state"])
            setting = self.worlds.saved(request["save_id"]) if self.worlds else None
            rules = setting.profile.item_use_rules if setting else []
            adopted = setting.profile.adopted_keys() if setting else None
            validate_item_uses(state, rules)
            library = LibraryReader(self.library_url, state, self.library_transport,
                                    frozen_definitions=setting.definitions if setting else None,
                                    adopted_definitions=adopted)
            if setting:
                library.world_context = setting.model_context()
                if rules:
                    library.world_context["item_use_constraints"] = item_use_constraints(state, rules)
                for ref in setting.profile.foundations:
                    library.get(ref.category, ref.key)
            history_reads = []

            def read_history(before, limit):
                """记录本次实际返回的历史范围与散列；不增加读取、不复制正文或其他命卷资料。"""
                rows = self.repo.history(request["save_id"], before, limit)
                history_reads.append({
                    "before_turn": before, "limit": limit,
                    "turns": [row["turn"] for row in rows],
                    "content_hash": hashlib.sha256(
                        json.dumps(rows, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
                    ).hexdigest(),
                })
                return rows

            candidate = Candidate.model_validate(
                gateway.generate(
                    state,
                    request["action"],
                    library,
                    read_history,
                    lambda: self.repo.spend_model_call(request, self.call_limit),
                    self.repo.record_model_call,
                )
            )
            references = list(library.reads.values())
            new_state = apply_candidate(state, candidate, references, turn=snapshot["revision"] + 1,
                                        adopted_definitions=adopted, item_use_rules=rules)
            metadata = (
                gateway.audit_metadata()
                if hasattr(gateway, "audit_metadata")
                else {"mode": gateway.mode}
            )
            audit = {
                **metadata, "inventory_protocol": "events-1", "history_reads": history_reads,
                "world_setting_hash": setting.digest() if setting else None,
                "declared_definition_uses": [ref.model_dump() for ref in candidate.used_definitions],
                "item_use_protocol": "single-protection-1" if rules else None,
            }
            self.repo.commit(request, candidate, new_state, references, gateway.mode, audit)
        except RuntimeProblem as exc:
            self.repo.fail(request, exc.code, exc.message)
        except Exception as exc:
            # 不输出提供商异常正文或凭证；失败边界由原请求恢复，无隐式付费重试。
            logging.getLogger(__name__).warning(
                "action %s failed: %s", request["request_id"], type(exc).__name__
            )
            if isinstance(exc, ValidationError):
                logging.getLogger(__name__).warning(
                    "validation fields: %s",
                    [
                        {"loc": list(error["loc"]), "type": error["type"]}
                        for error in exc.errors(include_input=False, include_url=False)
                    ],
                )
            self.repo.fail(request, "GENERATION_FAILED", "这一回暂时未能完成，存档没有变化，原行动已保留。")
        finally:
            if library:
                library.close()
