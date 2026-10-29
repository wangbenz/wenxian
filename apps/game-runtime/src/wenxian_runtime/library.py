"""只读 HTTP 资料客户端；限定路径、防分页越界，保存本轮实际使用的完整定义。"""

import re
from copy import deepcopy

import httpx

from .domain import Category, DefinitionRef, GameState, RuntimeProblem


class LibraryReader:
    def __init__(self, origin: str, state: GameState | None = None, transport=None, *, definition_pins=None,
                 frozen_definitions=None, adopted_definitions: set[str] | None = None):
        self.client = httpx.Client(
            base_url=origin.rstrip("/") + "/", timeout=5, follow_redirects=False, transport=transport
        )
        self.pins = state.definition_pins if state else definition_pins or {}
        self.frozen = frozen_definitions or {}
        self.adopted = adopted_definitions
        self.world_context = None
        self.reads: dict[str, dict] = {}

    def close(self):
        self.client.close()

    def get(self, category: str, key: str, *, revision: int | None = None) -> dict:
        ref = DefinitionRef(category=category, key=key)
        pin_key = f"{ref.category}:{ref.key}"
        if self.adopted is not None and pin_key not in self.adopted:
            raise RuntimeProblem("DEFINITION_NOT_ADOPTED", "这条公共资料尚未纳入本局；不能据此授予能力或执行数值规则。", 422)
        pin = self.pins.get(pin_key)
        try:
            requested_revision = pin["revision"] if pin else revision
            if pin and revision is not None and revision != pin["revision"]:
                raise ValueError("requested revision mismatch")
            if pin_key in self.frozen:
                record = self.frozen[pin_key]
                if (requested_revision is not None and record["revision"] != requested_revision
                        or pin and (pin["revision"], pin["content_hash"]) != (record["revision"], record["content_hash"])):
                    raise ValueError("bound definition mismatch")
                self.reads[pin_key] = deepcopy(record)
                return self.reads[pin_key]
            if requested_revision is not None:
                response = self.client.get(f"api/library/v1/revisions/{category}:{key}:{requested_revision}/")
                response.raise_for_status()
                payload = response.json()
                if payload["revision"] != requested_revision:
                    raise ValueError("historical revision mismatch")
                data = payload["content"]
                revision, content_hash = requested_revision, payload["content_hash"]
            else:
                response = self.client.get(f"api/library/v1/{ref.category}/{ref.key}/")
                response.raise_for_status()
                data = response.json()
                revision, content_hash = data["revision"], data["content_hash"]
            if (
                not re.fullmatch(r"[a-f0-9]{64}", content_hash)
                or not isinstance(revision, int)
                or revision < 1
            ):
                raise ValueError("invalid revision metadata")
            if data["key"] != ref.key or pin and pin["content_hash"] != content_hash:
                raise ValueError("pin mismatch")
            record = {
                "category": ref.category,
                "key": ref.key,
                "revision": revision,
                "content_hash": content_hash,
                "definition": data,
            }
            self.reads[pin_key] = record
            return record
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            raise RuntimeProblem(
                "LIBRARY_UNAVAILABLE", "需要的资料暂时无法确认，原行动已保留。", 503
            ) from exc

    def search(self, category: str, name: str) -> list[dict]:
        category = DefinitionRef(category=category, key="query").category
        if not name.strip() or len(name) > 80:
            raise ValueError("查询名称长度须在 1 到 80 字")
        if self.adopted is not None:
            # 清单内的冻结候选可离线检索；名称与别名只定位资料，不裁决剧情或持有。
            query = name.strip().casefold()
            return [
                {"key": record["key"], "name": record["definition"]["name"],
                 "entry_kind": record["definition"]["entry_kind"]}
                for key, record in self.frozen.items()
                if key in self.adopted and record["category"] == category
                and any(query in value.casefold() for value in
                        [record["definition"]["name"], *record["definition"].get("aliases", [])])
            ][:5]
        try:
            response = self.client.get(f"api/library/v1/{category}/", params={"name": name, "limit": 5})
            response.raise_for_status()
            records = response.json()["results"][:5]
            # 搜索仅给候选；需要完整定义时 get，并以实际读过的版本保存。
            return [{"key": r["key"], "name": r["name"], "entry_kind": r["entry_kind"]} for r in records]
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            raise RuntimeProblem("LIBRARY_UNAVAILABLE", "资料查询暂时无法完成。", 503) from exc

    def browse(self, category: Category, query: str, offset: int, limit: int = 12) -> dict:
        """玩家目录读取不记为叙事实际引用，不处理上游 next URL 或加载其他分类。"""
        try:
            response = self.client.get(
                f"api/library/v1/{category}/",
                params={"name_contains": query, "limit": limit, "offset": offset},
            )
            response.raise_for_status()
            data = response.json()
            if not isinstance(data["count"], int) or data["count"] < 0:
                raise ValueError("invalid count")
            if not isinstance(data["results"], list) or len(data["results"]) > limit:
                raise ValueError("invalid page")
            return data
        except (httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
            raise RuntimeProblem("LIBRARY_UNAVAILABLE", "资料暂时无法读取，请稍后重试。", 503) from exc
