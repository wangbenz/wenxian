"""玩家资料查阅应用服务；只读公共定义及本局已采用的版本，不授予资产、不写引用。"""

import json
from urllib.parse import urlparse

from .domain import RuntimeProblem
from .library import LibraryReader
from .repository import ROOT

CATEGORIES = [
    {"id": "realms", "name": "修行境界"},
    {"id": "arts", "name": "功法与术法"},
    {"id": "items", "name": "物品"},
    {"id": "place-types", "name": "场所"},
    {"id": "facility-types", "name": "设施"},
    {"id": "recipes", "name": "配方"},
]


def text_field(data, name):
    value = data.get(name, "")
    if not isinstance(value, str):
        raise ValueError("invalid reference text")
    return value


def entry_summary(data):
    return {
        "key": data["key"],
        "name": data["name"],
        "description": text_field(data, "description"),
        "kind": "概述" if data["entry_kind"] == "overview" else "定义",
        "example": data.get("source") == "问仙录测试设定",
    }


class ReferenceService:
    """目录与详情失败仅影响查阅；不调用模型、加载整份游戏状态或修改本局版本。"""

    def __init__(self, repository, library_url, library_transport=None, worlds=None):
        self.repo, self.library_url, self.transport = repository, library_url, library_transport
        self.worlds = worlds

    def overview(self, save_id=None):
        setting = self.worlds.saved(save_id) if self.worlds and save_id else None
        if setting:
            sections = [{"title": "本局开篇", "text": "\n".join(setting.profile.opening.narrative)}]
            for ref in setting.profile.foundations:
                data = setting.definitions[f"{ref.category}:{ref.key}"]["definition"]
                text = "\n".join(data.get(field, "") for field in ("description", "usage", "limitations") if data.get(field))
                sections.append({"title": data["name"], "text": text})
            return {
                "world": {"title": setting.profile.title, "scope": setting.profile.scope + " · 本局固定资料",
                          "summary": setting.profile.summary, "sections": sections},
                "categories": CATEGORIES,
            }
        return {
            "world": json.loads((ROOT / "resources/world-reference.json").read_text()),
            "categories": CATEGORIES,
        }

    def browse(self, category, query, offset):
        reader = LibraryReader(self.library_url, transport=self.transport)
        try:
            data = reader.browse(category, query.strip(), offset)
            return {"count": data["count"], "offset": offset, "limit": 12,
                    "results": [entry_summary(row) for row in data["results"]]}
        except (KeyError, ValueError, TypeError) as exc:
            raise RuntimeProblem("LIBRARY_UNAVAILABLE", "资料暂时无法读取，请稍后重试。", 503) from exc
        finally:
            reader.close()

    def detail(self, save_id, category, key):
        setting = self.worlds.saved(save_id) if self.worlds else None
        pins = self.repo.definition_pins(save_id)
        frozen = setting.definitions if setting else {}
        reader = LibraryReader(
            self.library_url, transport=self.transport,
            definition_pins={**frozen, **pins}, frozen_definitions=frozen,
        )
        try:
            record = reader.get(category, key)
            data = record["definition"]
            sections = [
                {"title": title, "text": text_field(data, field)}
                for field, title in [("description", "介绍"), ("usage", "用途与用法"),
                                     ("limitations", "限制")]
                if data.get(field)
            ]
            sources = []
            for source in data.get("sources", [])[:20]:
                url = text_field(source, "url")
                parsed = urlparse(url)
                sources.append({
                    "title": text_field(source, "title"), "locator": text_field(source, "locator"),
                    "supports": text_field(source, "supports"),
                    "url": url if parsed.scheme in ("http", "https") and parsed.netloc else "",
                })
            if not sources and data.get("source"):
                sources.append({"title": text_field(data, "source"), "locator": "", "supports": "", "url": ""})
            for field, title in [("typical_realm", "常见修行阶段"), ("minimum_realm", "资料标注的最低阶段"),
                                 ("facility_type", "资料标注的设施"), ("output_item", "资料标注的产物")]:
                if data.get(field):
                    sections.append({"title": title, "text": data[field]["name"]})
            if data.get("ingredients"):
                sections.append({"title": "资料标注的材料", "text": "、".join(
                    f"{row['item']['name']} × {row['quantity']}" for row in data["ingredients"]
                )})
            if data.get("output_quantity"):
                sections.append({"title": "资料标注的产出数量", "text": str(data["output_quantity"])})
            return {**entry_summary(data), "revision": record["revision"],
                    "scope": "save" if f"{category}:{key}" in reader.pins else "public",
                    "sections": sections, "sources": sources}
        except (KeyError, ValueError, TypeError) as exc:
            raise RuntimeProblem("LIBRARY_UNAVAILABLE", "这条资料暂时无法读取，请返回目录后重试。", 503) from exc
        finally:
            reader.close()
