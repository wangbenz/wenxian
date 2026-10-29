"""本局设定协议：公共定义与开篇事实分开；快照有明确格式版本，不能静默重绑定。"""

import hashlib
import json
import re
from typing import Literal

from pydantic import Field, model_serializer, model_validator

from .domain import (
    Ability,
    Character,
    CharacterCreation,
    Choice,
    DefinitionRef,
    GameState,
    Item,
    ItemOrigin,
    ItemUseRule,
    Key,
    StrictModel,
    Text,
)


def ref_key(ref: DefinitionRef) -> str:
    return f"{ref.category}:{ref.key}"


class OpeningPremise(StrictModel):
    """场景作者声明的开局，不把公共资料中的可能性当成玩家已经获得的东西。"""

    id: Literal["qingmu-trial"]
    realm: DefinitionRef
    realm_stage: Text
    practice: DefinitionRef
    practice_id: Key
    practice_stage: Text
    context: list[DefinitionRef]
    location: Text
    scene_title: Text
    condition: Text
    narrative: list[Text] = Field(min_length=1, max_length=12)
    choices: list[Choice]
    items: list[Item]
    characters: list[Character]
    time: dict[str, str | int]


class AdoptedDefinition(DefinitionRef):
    """世界配置审定的具体修订；公共当前版更新不能自动替换下一次开卷的采用版本。"""

    revision: int = Field(ge=1, strict=True)


class WorldProfile(StrictModel):
    """服务器选择适用于本段游戏的公共条目；不按词命中自动批准玩法或引入测试公式。"""

    id: Key
    revision: int = Field(ge=1, strict=True)
    title: Text
    scope: Text
    summary: Text
    foundations: list[DefinitionRef] = Field(min_length=1, max_length=8)
    opening: OpeningPremise
    adopted_definitions: list[AdoptedDefinition] | None = Field(default=None, min_length=1, max_length=80)
    item_use_rules: list[ItemUseRule] = Field(default_factory=list, max_length=20)

    def references(self) -> list[DefinitionRef]:
        values = [*self.foundations, self.opening.realm, self.opening.practice, *self.opening.context,
                  *(self.adopted_definitions or [])]
        return list({ref_key(ref): ref for ref in values}.values())

    def adopted_keys(self) -> set[str] | None:
        return {ref_key(ref) for ref in self.adopted_definitions} if self.adopted_definitions is not None else None


class WorldSetting(StrictModel):
    """完整快照只绑定新卷；基础定义不再依赖资料库当前版，旧卷缺绑定保持缺失。"""

    schema_version: Literal["library-setting-1", "library-setting-2"] = "library-setting-1"
    profile: WorldProfile
    definitions: dict[str, dict]

    @model_validator(mode="after")
    def complete_snapshot(self):
        expected = {ref_key(ref) for ref in self.profile.references()}
        adopted = self.profile.adopted_keys()
        if self.schema_version == "library-setting-1":
            if adopted is not None or self.profile.item_use_rules:
                raise ValueError("旧设定格式不能携带新的采用清单或使用规则")
        elif adopted is None or adopted != expected or len(adopted) != len(self.profile.adopted_definitions):
            raise ValueError("本局采用清单须完整且不重复")
        rule_ids, rule_refs = set(), set()
        for rule in self.profile.item_use_rules:
            if (rule.definition.category != "items" or ref_key(rule.definition) not in (adopted or set())
                    or rule.id in rule_ids or ref_key(rule.definition) in rule_refs):
                raise ValueError("本局使用规则未采用或重复声明")
            rule_ids.add(rule.id)
            rule_refs.add(ref_key(rule.definition))
        if expected != set(self.definitions):
            raise ValueError("本局设定条目不完整")
        for ref in self.profile.references():
            record = self.definitions[ref_key(ref)]
            if (record.get("category"), record.get("key")) != (ref.category, ref.key):
                raise ValueError("本局设定引用不一致")
            if type(record.get("revision")) is not int or record["revision"] < 1:
                raise ValueError("本局设定修订无效")
            if isinstance(ref, AdoptedDefinition) and record["revision"] != ref.revision:
                raise ValueError("本局资料与审定修订不一致")
            if not isinstance(record.get("content_hash"), str) or not re.fullmatch(r"[a-f0-9]{64}", record["content_hash"]):
                raise ValueError("本局设定内容散列无效")
            definition = record.get("definition", {})
            if not isinstance(definition, dict) or definition.get("key") != ref.key or not isinstance(definition.get("name"), str) or not definition["name"].strip():
                raise ValueError("本局设定正文无效")
        if self.profile.opening.realm.category != "realms" or self.profile.opening.practice.category != "arts":
            raise ValueError("开局境界或功法类别不正确")
        return self

    @model_serializer(mode="wrap")
    def preserve_old_snapshot(self, handler):
        """旧格式的规范序列化保持原样，新增默认字段不得改变已保存设定的散列。"""
        data = handler(self)
        if self.schema_version == "library-setting-1" and "profile" in data:
            data["profile"].pop("adopted_definitions", None)
            data["profile"].pop("item_use_rules", None)
        return data

    def digest(self) -> str:
        value = json.dumps(self.model_dump(mode="json"), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(value.encode()).hexdigest()

    def opening_state(self, character: CharacterCreation | None = None) -> GameState:
        premise = self.profile.opening
        realm = self.definitions[ref_key(premise.realm)]["definition"]
        practice = self.definitions[ref_key(premise.practice)]["definition"]
        return GameState(
            name=character.name if character else "林默",
            goal=character.goal if character else "先在青溪镇落脚，再寻找继续修行的机会",
            gender=character.gender if character else "未设定",
            origin=character.origin if character else "",
            story_preference=character.story_preference if character else "",
            realm=realm["name"] + premise.realm_stage,
            condition=premise.condition, location=premise.location,
            scene_title=premise.scene_title, narrative=list(premise.narrative), choices=premise.choices,
            items=premise.items,
            item_origins={item.id: ItemOrigin(turn=0, event_id="opening", source="玩家确认的本局开篇", kind="opening")
                          for item in premise.items},
            abilities=[Ability(id=premise.practice_id, name=practice["name"], kind="TECHNIQUE",
                               stage=premise.practice_stage, source="玩家确认的本局开篇",
                               description=practice.get("description") or practice.get("usage") or practice["name"],
                               definition=premise.practice)],
            characters=premise.characters, time=premise.time,
        )

    def model_context(self) -> dict:
        """固定公共背景持续可见；开局只标为过去前提，不能把当前场景复位。"""
        context = {
            "schema_version": self.schema_version,
            "profile_id": self.profile.id, "setting_hash": self.digest(),
            "scope": self.profile.scope, "summary": self.profile.summary,
            "background_definitions": [self.definitions[ref_key(ref)] for ref in self.profile.foundations],
            "opening_premise": {
                "confirmed_at_turn": 0,
                "location": self.profile.opening.location,
                "initial_realm": self.opening_state().realm,
                "initial_practice": self.profile.opening.practice.model_dump(),
                "note": "仅说明玩家确认的开局；人物当前状态、拥有和地点以 confirmed_state 与已提交回合为准。",
            },
            "bound_definitions": [
                {"category": record["category"], "key": record["key"], "revision": record["revision"],
                 "name": record["definition"]["name"]}
                for record in self.definitions.values()
            ],
        }
        if self.schema_version == "library-setting-2":
            context["adopted_definitions"] = context["bound_definitions"]
            context["item_use_rules"] = [rule.model_dump(mode="json") for rule in self.profile.item_use_rules]
        return context
