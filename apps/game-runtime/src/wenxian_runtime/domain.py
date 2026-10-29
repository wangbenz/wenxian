"""类型化的本局事实与模型建议；结构校验失败时整个回合不提交。"""

from collections import Counter
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]
Key = Annotated[str, StringConstraints(pattern=r"^[a-z0-9][a-z0-9:_-]{0,99}$")]
Category = Literal["realms", "arts", "items", "place-types", "facility-types", "recipes"]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DefinitionRef(StrictModel):
    category: Category
    key: Key


class Choice(StrictModel):
    id: Key
    label: Text
    hint: Text


class Item(StrictModel):
    id: Key
    name: Text
    category: Text
    status: Text = Field(description="物品当前状态；临时效果通过状态事件与使用记录表达，不写进固有说明")
    quantity: int = Field(ge=0, le=1000000, strict=True,
                          description="当前库存数量，以采用定义及使用规则为准；变化必须与物品事件一致，零值仍保留历史")
    description: Text = Field(description="稳定外形与固有用途，不写获得经过、当前位置、当前激活效果或剩余数量；同一身份后续沿用")
    definition: DefinitionRef | None = None


class Ability(StrictModel):
    """已确认本领的身份与首次来历保持稳定；进展通过 stage 和本轮信息记录，不静默换定义。"""

    id: Key
    name: Text
    kind: Literal["TALENT", "TECHNIQUE", "SPELL"]
    stage: Text = Field(description="当前熟练或掌握阶段；更新须有本轮行动与实际进展依据，不按固定数值推算")
    source: Text = Field(description="首次确认的师承、秘籍或取得来源；同一编号沿用，后续训练经过写入本轮信息")
    description: Text = Field(description="本领固有用途与边界；同一编号沿用，临时表现或新增认识写入本轮信息")
    definition: DefinitionRef | None = Field(default=None, description="已采用的 arts 公共定义；同一编号沿用，本局原创无公共定义时可为空")


class Character(StrictModel):
    id: Key
    name: Text
    identity: Text
    state: Text
    location: Text
    relation: Text


class Information(StrictModel):
    kind: Literal["fact", "observation", "statement", "rumor", "plan"]
    subject: Text
    text: Text
    source: Text = Field(description="谁观察/声明，事实的本轮原因。计划与传闻不可标为 fact")


class ItemTransition(StrictModel):
    """同一物品实例的数量前后值与可选状态，前值须匹配权威存档或本轮前一事件。"""

    item_id: Key
    before: int = Field(ge=0, le=1000000, strict=True)
    after: int = Field(ge=0, le=1000000, strict=True)
    status: Text | None = Field(default=None, description="状态改变时明确提供；不能在这里改名称/用途")


class ItemEvent(StrictModel):
    """物品变化依据；语义来源仍由 AI 解释，程序不凭词表认定剧情真实或行动已获授权。"""

    id: Key = Field(description="本回合内唯一的事件编号")
    kind: Literal["acquire", "consume", "transfer", "lose", "transform", "status"]
    source: Text = Field(description="实际给予者、接收者、消耗行为或变化来源；承诺不算已经获得")
    reason: Text = Field(description="只解释本轮已执行行动或直接后果为什么产生该事件，不用计划或公共定义代替；数量和状态由 changes 表达，不重复结算")
    changes: list[ItemTransition] = Field(min_length=1, max_length=20)


class ItemOrigin(StrictModel):
    """服务端从首个已确认获得/改造事件生成的来历；AI 不可直接写入或覆盖。"""

    turn: int = Field(ge=0, strict=True)
    event_id: Key
    source: Text
    kind: Literal["opening", "acquire", "transform"]
    predecessors: list[Key] = Field(default_factory=list)


class ItemUseRule(StrictModel):
    """本局明确采用的有限使用规则；程序只保护生命周期，不判断攻击强度或剧情授权。"""

    id: Key
    definition: DefinitionRef
    kind: Literal["single-protection"]
    name: Text


UseEnd = Literal["effective_protection", "dismissed", "destroyed"]


class ItemUseEvent(StrictModel):
    """使用建议关联同回合物品事件；激活不扣数量，结束必须消耗一件，不能覆盖旧使用。"""

    id: Key
    use_id: Key = Field(description="一次使用的稳定编号；结束时沿用激活时的编号，已结束编号不能重用")
    item_id: Key
    rule_id: Key
    kind: Literal["activate", "end"]
    item_event_id: Key = Field(description="同回合对应的单件 status 或 consume 物品事件编号")
    end_reason: UseEnd | None = None
    reason: Text = Field(description="只解释本轮哪项已执行行动或直接后果导致激活或结束，不重复数量与状态；询问、计划或无害接触不能代替")


class ItemUseState(StrictModel):
    """服务器由合法使用事件派生的状态；模型不可直接写入，结束记录保留防止重复激活。"""

    id: Key
    item_id: Key
    rule_id: Key
    name: Text
    status: Literal["active", "ended"]
    activated_turn: int = Field(ge=1, strict=True)
    activation_event_id: Key
    ended_turn: int | None = Field(default=None, ge=1, strict=True)
    end_event_id: Key | None = None
    end_reason: UseEnd | None = None

    @model_validator(mode="after")
    def consistent_lifecycle(self):
        ended = (self.ended_turn, self.end_event_id, self.end_reason)
        if self.status == "active" and any(value is not None for value in ended):
            raise ValueError("生效中的使用不能带有结束记录")
        if self.status == "ended" and (any(value is None for value in ended) or self.ended_turn < self.activated_turn):
            raise ValueError("已结束的使用必须有完整且不早于激活的结束记录")
        return self


class Change(StrictModel):
    """当前状态的增量建议，历史追加保存；禁止不受约束的 JSON Patch。"""

    reason: Text = Field(description="只简述本轮授权行动或直接后果的依据；不在此另写一套数量、消耗或使用状态，实际结果由对应字段与事件表达")
    location: Text | None = None
    condition: Text | None = None
    realm: Text | None = None
    items: list[Item] = Field(
        default_factory=list,
        max_length=20,
        description="新物品的完整记录；既有物品身份/用途不可同编号改写。数量/状态须与事件最终值一致",
    )
    item_events: list[ItemEvent] = Field(
        default_factory=list, max_length=20, description="获得/消耗/转移/失去/改造/状态变化；无变化则留空"
    )
    item_use_events: list[ItemUseEvent] = Field(
        default_factory=list, max_length=20,
        description="按实际顺序记录已采用规则下的激活/结束；每条关联独立物品事件，无使用变化留空",
    )
    abilities: list[Ability] = Field(default_factory=list, max_length=10)
    characters: list[Character] = Field(default_factory=list, max_length=10)


class Candidate(StrictModel):
    """模型只能提出本轮正文、建议和信息；权限、请求状态与版本均由服务端管理。"""

    scene_title: Text
    narrative: list[Text] = Field(min_length=1, max_length=12)
    choices: list[Choice] = Field(default_factory=list, max_length=4)
    change: Change | None = Field(
        default=None,
        description="仅包含当前状态增量和原因，不包含 information；信息记录属于 Candidate 最外层",
    )
    information: list[Information] = Field(
        default_factory=list,
        max_length=20,
        description="最外层的信息记录，区分事实、观察、声明、传闻和计划，不放进 change",
    )
    used_definitions: list[DefinitionRef] = Field(
        default_factory=list, max_length=20,
        description="本轮结果实际依据的公共定义；只能填写上下文或工具已经提供的条目，不以查询代替拥有或成功",
    )

    @model_validator(mode="before")
    @classmethod
    def normalize_information_position(cls, value):
        """兼容信息列表被错放到 change 的单一格式偏差；原值保留，冲突双写及其他未知字段仍拒绝。"""
        if (
            isinstance(value, dict)
            and isinstance(value.get("change"), dict)
            and "information" in value["change"]
        ):
            nested = value["change"]["information"]
            top = value.get("information")
            if top is not None and top != [] and top != nested:
                raise ValueError("顶层与状态变化内的信息记录冲突")
            value = {
                **value,
                "change": {key: item for key, item in value["change"].items() if key != "information"},
                "information": nested,
            }
        return value


class CharacterCreation(StrictModel):
    """玩家确认的创角资料；可选来历/偏好不自行授予资产、境界或预定结果。"""

    opening_id: Literal["qingmu-trial"]
    name: Annotated[str, StringConstraints(strip_whitespace=True, min_length=2, max_length=6)]
    goal: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=240)]
    gender: Literal["未设定", "男", "女"] = "未设定"
    origin: Annotated[str, StringConstraints(strip_whitespace=True, max_length=240)] = ""
    story_preference: Annotated[str, StringConstraints(strip_whitespace=True, max_length=240)] = ""
    world_setting_hash: Annotated[str, StringConstraints(pattern=r"^[a-f0-9]{64}$")] | None = None


class GameState(StrictModel):
    name: str
    goal: str
    # 旧投影缺省读取兼容，不回写已提交开局或历史。
    gender: str = "未设定"
    origin: str = ""
    story_preference: str = ""
    realm: str
    condition: str
    location: str
    scene_title: str
    narrative: list[str]
    choices: list[Choice]
    items: list[Item]
    item_origins: dict[str, ItemOrigin] = Field(default_factory=dict)
    item_uses: dict[str, ItemUseState] = Field(default_factory=dict)
    abilities: list[Ability]
    characters: list[Character]
    # 当前试玩不自动推进游戏钟；UI 仅展示明确的开局时间。
    time: dict[str, str | int]
    definition_pins: dict[str, dict] = Field(default_factory=dict)


class ActionInput(StrictModel):
    request_id: Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{8,80}$")]
    expected_revision: int = Field(ge=0, strict=True)
    action: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]


class RuntimeProblem(Exception):
    def __init__(self, code: str, message: str, status: int = 409):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


def opening(character: CharacterCreation | None = None) -> GameState:
    """旧格式领域验证样例；新开卷统一经过 WorldSettingService，不从此函数创建运行命卷。"""
    return GameState(
        name=character.name if character else "林默",
        goal=character.goal if character else "先在青溪镇落脚，再寻找继续修行的机会",
        gender=character.gender if character else "未设定",
        origin=character.origin if character else "",
        story_preference=character.story_preference if character else "",
        realm="炼气初期",
        condition="安好",
        location="青溪镇·青木坊门前",
        scene_title="青木坊的来货",
        narrative=[
            "你刚到青溪镇，行囊里只有随身衣物。眼前的青木坊正忙着收一批药材。",
            "掌柜翻过货单，看了看门边的竹筐：‘要是愿意搭把手，先帮我核一核来货。食宿的事，咱们可以谈。’",
            "送货人还在门口等着。你可以接下差事、先问清来路，也可以离开这里，另寻机会。",
        ],
        choices=[
            Choice(id="help", label="先问清核货要求与食宿条件", hint="谈妥之后，再决定是否接下。"),
            Choice(id="ask", label="向送货人打听这批药材的来路", hint="先了解情况，再考虑是否参与。"),
            Choice(id="leave", label="离开青木坊，去河边吐纳", hint="暂不接差事，先安定自己的修行。"),
        ],
        items=[
            Item(
                id="clothes",
                name="随身衣物",
                category="随身物",
                status="完好",
                quantity=1,
                description="出门时带着的一套衣物。",
            )
        ],
        item_origins={
            "clothes": ItemOrigin(turn=0, event_id="opening", source="创角时确认的试玩开篇", kind="opening")
        },
        abilities=[
            Ability(
                id="breathing",
                name="基础吐纳法",
                kind="TECHNIQUE",
                stage="初学",
                source="试玩开局已确认掌握",
                description="能够尝试静坐调息，具体效果需结合本局情况。",
                definition=DefinitionRef(category="arts", key="basic-breathing"),
            )
        ],
        characters=[
            Character(
                id="shopkeeper",
                name="青木坊掌柜",
                identity="药材铺掌柜",
                state="核对货单",
                location="青木坊",
                relation="初次见面",
            ),
            Character(
                id="carrier",
                name="送货人",
                identity="来货的送货人",
                state="等候核货",
                location="青木坊门前",
                relation="尚未交谈",
            ),
        ],
        time={"year": 1, "day": 1, "phase": "MORNING"},
    )


def apply_candidate(
    state: GameState, candidate: Candidate, references: list[dict], *, turn: int,
    adopted_definitions: set[str] | None = None, item_use_rules: list[ItemUseRule] | None = None,
) -> GameState:
    """校验实际读过的定义，增量归并；不凭正文或关键词推算本局事实。"""
    validate_item_uses(state, item_use_rules or [])
    result = state.model_copy(deep=True)
    known = {(r["category"], r["key"]): r for r in references}
    if adopted_definitions is not None and any(f"{category}:{key}" not in adopted_definitions for category, key in known):
        raise RuntimeProblem("DEFINITION_NOT_ADOPTED", "这一回的资料不在本局采用范围内，原行动已保留。", 422)
    if any((ref.category, ref.key) not in known for ref in candidate.used_definitions):
        raise RuntimeProblem("UNREAD_DEFINITION", "这一回声明的资料依据尚未实际读取，原命卷已保留。", 422)
    c = candidate.change
    if c:
        for records in (c.items, c.abilities, c.characters):
            ids = [record.id for record in records]
            if len(set(ids)) != len(ids):
                raise RuntimeProblem("INVALID_CANDIDATE", "这一回的变化未能确认，请保留行动后重试。", 422)
        for record in [*c.items, *c.abilities]:
            if record.definition and (record.definition.category, record.definition.key) not in known:
                raise RuntimeProblem("UNREAD_DEFINITION", "这一回的资料依据未能确认，请稍后重试。", 422)
        apply_item_events(result, c, known, turn)
        apply_item_use_events(result, c, item_use_rules or [], turn)
        apply_ability_changes(result, c.abilities)
        for field in ("location", "condition", "realm"):
            value = getattr(c, field)
            if value is not None:
                setattr(result, field, value)
        characters = {record.id: record for record in result.characters}
        characters.update({record.id: record for record in c.characters})
        result.characters = list(characters.values())
    for ref in references:
        pin_key = f"{ref['category']}:{ref['key']}"
        old = result.definition_pins.get(pin_key)
        if old and (old["revision"], old["content_hash"]) != (ref["revision"], ref["content_hash"]):
            raise RuntimeProblem("DEFINITION_CHANGED", "本局资料版本未能确认，请稍后重试。", 422)
        result.definition_pins[pin_key] = ref
    result.scene_title, result.narrative, result.choices = (
        candidate.scene_title,
        candidate.narrative,
        candidate.choices,
    )
    return result


def apply_ability_changes(state: GameState, abilities: list[Ability]):
    """只归并明确提出的本领变化；同编号固有资料冲突整体拒绝，不凭名称裁决学习或进阶。"""
    if not abilities:
        return
    records = {ability.id: ability for ability in state.abilities}
    for proposed in abilities:
        if proposed.definition and proposed.definition.category != "arts":
            raise RuntimeProblem("INVALID_ABILITY_DEFINITION", "这次本领的资料类别未能确认，原行动已保留。", 422)
        current = records.get(proposed.id)
        if current and any(getattr(current, field) != getattr(proposed, field)
                           for field in ("name", "kind", "source", "description", "definition")):
            raise RuntimeProblem("ABILITY_IDENTITY_CONFLICT", "这次本领变化与已有记录不一致，命卷未改变，原行动已保留。", 422)
        records[proposed.id] = proposed
    state.abilities = list(records.values())


def validate_item_uses(state: GameState, rules: list[ItemUseRule]):
    """确认已保存的使用仍对应原物与原规则；不从状态文字猜测护光或伤害。"""
    configured = {rule.id: rule for rule in rules}
    items = {item.id: item for item in state.items}
    active = Counter()
    for key, use in state.item_uses.items():
        rule, item = configured.get(use.rule_id), items.get(use.item_id)
        if key != use.id or not rule or not item or item.definition != rule.definition or use.name != rule.name:
            raise RuntimeProblem("ITEM_USE_CONFLICT", "物品的使用记录暂时无法确认，原命卷已保留。", 422)
        if use.status == "active":
            active[use.item_id] += 1
    if any(count > items[item_id].quantity for item_id, count in active.items()):
        raise RuntimeProblem("ITEM_USE_CONFLICT", "生效中的物品缺少对应持有记录，请保留原行动。", 422)


def item_use_constraints(state: GameState, rules: list[ItemUseRule]) -> list[dict]:
    """投影已采用规则下当前一次结束的数量要求；不选择结束、不修复候选或修改事实。"""
    validate_item_uses(state, rules)
    quantities = {item.id: item.quantity for item in state.items}
    return [
        {"use_id": use.id, "item_id": use.item_id, "rule_id": use.rule_id,
         "next_end_inventory_event": {"kind": "consume", "before": quantities[use.item_id],
                                      "after": quantities[use.item_id] - 1},
         "allowed_end_reasons": ["effective_protection", "dismissed", "destroyed"]}
        for use in state.item_uses.values() if use.status == "active"
    ]


def apply_item_use_events(state: GameState, change: Change, rules: list[ItemUseRule], turn: int):
    """执行已声明的一次护持生命周期；数量由物品事件结算，语义强弱仍由叙事解释和真实回放检查。"""
    configured = {rule.id: rule for rule in rules}
    items = {item.id: item for item in state.items}
    inventory_events = {event.id: (index, event) for index, event in enumerate(change.item_events)}
    linked, event_ids, previous_index = {}, set(), -1
    for event in change.item_use_events:
        rule, item = configured.get(event.rule_id), items.get(event.item_id)
        if not rule or not item or rule.definition.category != "items" or item.definition != rule.definition:
            raise RuntimeProblem("ITEM_USE_UNSUPPORTED", "这件物品尚未采用对应的使用规则，原行动已保留。", 422)
        entry = inventory_events.get(event.item_event_id)
        if (not entry or event.id in event_ids or event.item_event_id in linked
                or entry[0] <= previous_index or len(entry[1].changes) != 1
                or entry[1].changes[0].item_id != event.item_id):
            raise RuntimeProblem("ITEM_USE_EVENT_REQUIRED", "物品使用缺少顺序一致的变化记录，原行动已保留。", 422)
        linked[event.item_event_id] = event
        event_ids.add(event.id)
        previous_index = entry[0]
    # 逐个物品事件核对占用，不能先销毁正在用的一张、再补货来掩盖中途丢失的护持。
    for inventory_event in change.item_events:
        event = linked.get(inventory_event.id)
        if event:
            _apply_single_protection(state, event, inventory_event, configured[event.rule_id], turn)
        active = Counter(use.item_id for use in state.item_uses.values() if use.status == "active")
        if any(active[row.item_id] > row.after for row in inventory_event.changes):
            raise RuntimeProblem("ITEM_USE_CONFLICT", "物品已不在持有记录中，但对应使用尚未结束，原行动已保留。", 422)
    validate_item_uses(state, rules)


def _apply_single_protection(state, event, inventory_event, rule, turn):
    """单次护持只在明确激活、有效抵挡、主动散去或毁损事件下迁移，不从自然语言猜状态。"""
    row = inventory_event.changes[0]
    current = state.item_uses.get(event.use_id)
    if event.kind == "activate":
        active = sum(use.item_id == event.item_id and use.status == "active" for use in state.item_uses.values())
        if current or event.end_reason is not None or inventory_event.kind != "status" or row.before <= active:
            raise RuntimeProblem("ITEM_NOT_UNUSED", "这次使用没有对应的未用物品，请保留原行动。", 422)
        state.item_uses[event.use_id] = ItemUseState(
            id=event.use_id, item_id=event.item_id, rule_id=event.rule_id, name=rule.name,
            status="active", activated_turn=turn, activation_event_id=event.id,
        )
    else:
        if (not current or current.status != "active" or current.item_id != event.item_id
                or current.rule_id != event.rule_id or event.end_reason is None
                or turn < current.activated_turn
                or inventory_event.kind != "consume" or row.after != row.before - 1):
            raise RuntimeProblem("ITEM_USE_END_CONFLICT", "这次结束未对应一件正在使用的物品及其消耗，原行动已保留。", 422)
        state.item_uses[event.use_id] = ItemUseState.model_validate({**current.model_dump(),
            "status": "ended", "ended_turn": turn, "end_event_id": event.id, "end_reason": event.end_reason,
        })


def apply_item_events(state: GameState, change: Change, known: dict, turn: int):
    """保护实例身份、前后数量、来历与原子变化；不验证自然语言来源是否真实。"""
    records = {item.id: item for item in state.items}
    declared = {item.id: item for item in change.items}
    new_ids = set(declared) - set(records)

    def reject(code, message):
        raise RuntimeProblem(code, message, 422)

    for item_id, proposed in declared.items():
        old = records.get(item_id)
        if old and any(
            getattr(old, field) != getattr(proposed, field)
            for field in ("name", "category", "description", "definition")
        ):
            reject("ITEM_IDENTITY_CONFLICT", "这一回的物品身份与已有记录不一致，原命卷已保留。")
        if proposed.definition and proposed.definition.category != "items":
            reject("INVALID_ITEM_DEFINITION", "物品的资料类别未能确认，原命卷已保留。")
        if not old:
            records[item_id] = proposed.model_copy(update={"quantity": 0})

    event_ids = [event.id for event in change.item_events]
    if len(set(event_ids)) != len(event_ids):
        reject("INVALID_ITEM_EVENT", "这一回的物品变化记录重复，原命卷已保留。")
    created = set()
    for event in change.item_events:
        ids = [row.item_id for row in event.changes]
        if len(set(ids)) != len(ids):
            reject("INVALID_ITEM_EVENT", "一件物品在同一变化中出现多次，原命卷已保留。")
        deltas = [row.after - row.before for row in event.changes]
        valid = (
            event.kind == "acquire"
            and all(delta > 0 for delta in deltas)
            or event.kind in ("consume", "transfer", "lose")
            and all(delta < 0 for delta in deltas)
            or event.kind == "status"
            and all(delta == 0 for delta in deltas)
            and all(row.before > 0 and row.status is not None for row in event.changes)
            or event.kind == "transform"
            and any(delta < 0 for delta in deltas)
            and any(delta > 0 for delta in deltas)
            and all(delta != 0 for delta in deltas)
        )
        if not valid:
            reject("INVALID_ITEM_EVENT", "物品的变化类型与前后数量不一致，原命卷已保留。")
        predecessors = [row.item_id for row in event.changes if row.after < row.before]
        for row, delta in zip(event.changes, deltas):
            item = records.get(row.item_id)
            if not item:
                reject("UNKNOWN_ITEM", "这次变化中的物品尚未建立记录，原命卷已保留。")
            if row.before != item.quantity:
                reject("ITEM_QUANTITY_CONFLICT", "物品数量与已有记录不一致，原命卷已保留。")
            if item.definition and (item.definition.category, item.definition.key) not in known:
                reject("UNREAD_DEFINITION", "这一回的物品资料依据未能确认，请稍后重试。")
            if (
                event.kind == "transform"
                and delta > 0
                and (row.item_id not in new_ids or row.item_id in created)
            ):
                reject("ITEM_IDENTITY_CONFLICT", "改造后的物品需要独立记录，原物品及经历会保留。")
            if row.item_id in new_ids and row.item_id not in created:
                if event.kind not in ("acquire", "transform") or delta <= 0:
                    reject("ITEM_ORIGIN_REQUIRED", "新物品的获得来源尚未确认，原命卷已保留。")
                state.item_origins[row.item_id] = ItemOrigin(
                    turn=turn,
                    event_id=event.id,
                    kind=event.kind,
                    source=event.source,
                    predecessors=predecessors if event.kind == "transform" else [],
                )
                created.add(row.item_id)
            records[row.item_id] = item.model_copy(
                update={"quantity": row.after, "status": row.status or item.status}
            )
    if new_ids != created:
        reject("ITEM_ORIGIN_REQUIRED", "新物品尚缺已发生的获得记录，原命卷已保留。")
    for item_id, proposed in declared.items():
        actual = records[item_id]
        if proposed.quantity != actual.quantity or proposed.status != actual.status:
            reject("ITEM_EVENT_REQUIRED", "物品变化缺少一致的事件依据，原命卷已保留。")
    state.items = list(records.values())
