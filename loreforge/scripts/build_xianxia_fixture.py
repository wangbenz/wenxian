"""把通用修仙资料与境界主线转换为受限导入器可接受的原生fixture。

仅生成文件，不写数据库；分类用显式映射，不据关键词推导境界或游戏效果。
青禾坊人物、库存、事件不在此批次内。未知5e字段显式为null。
"""

from collections import Counter
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
AUTHOR = ROOT / "docs/cultivation-source-design-2026-09-27"
OUTPUT = ROOT / "data/xianxia"
DOCUMENT = "xianxia-core"
PREFIX = "xianxia_"
LABELS = {"realm": "境界", "practice": "功法", "spell": "法术", "artifact": "法器",
          "talisman": "符箓", "pill": "丹药", "material": "灵材", "creature": "妖兽",
          "setting": "场所与社会", "rule": "修行规则", "item": "工具"}
BASE_KINDS = {1: "rule", 2: "realm", 3: "realm", 4: "practice", 5: "spell", 6: "rule",
              7: "artifact", 8: "rule", 9: "rule", 10: "talisman", 11: "rule", 12: "material",
              13: "pill", 14: "rule", 15: "setting", 16: "setting", 17: "setting", 18: "artifact"}
PRACTICES = {"practice-embryonic-breath", "practice-daoyin", "practice-guarding-one"}
TOOLS = {"tool-pill-furnace", "tool-forging-furnace"}
RESOURCE = {"spell": "spells", "artifact": "magicitems", "talisman": "magicitems",
            "pill": "magicitems", "material": "items", "item": "items"}
MODEL = {"spells": "spell", "magicitems": "magicitem", "items": "item", "rules": "rule"}
PAGE = {"spells": "/spells/", "magicitems": "/magic-items/", "items": "/equipment/", "rules": "/rules/entries/"}


def row(model, key, **fields):
    return {"model": f"api_v2.{model}", "pk": key, "fields": fields}


def source_entries():
    text = (AUTHOR / "content-cards.md").read_text()
    references = dict(re.findall(r"^\[([^\]]+)\]:\s+(https?://\S+)", text, re.M))
    blocks = re.split(r"(?=^## \d{2}｜)", text, flags=re.M)[1:]
    entries = []
    for block in blocks:
        block = block.split("\n## 本版关系核对", 1)[0]
        number, name = re.match(r"## (\d+)｜([^\n]+)", block).groups()
        key = re.search(r"^条目标识：([a-z0-9-]+)", block, re.M).group(1)
        body = re.sub(r"^## [^\n]+\n|^条目标识：[^\n]*\n|^类别：[^\n]*\n", "", block, flags=re.M)
        body = re.sub(r'<a id="[^"]+"></a>', "", body)
        body = re.sub(r"\[([FHC]\d+)\]", lambda m: f"[{m[1]}]({references[m[1]]})", body)
        # 关联列表在下方以真实站内入口重建；不把编辑页相对地址带进资料正文。
        relation_text = re.search(r"关联：([^\n]+)", body)
        related_numbers = [int(n) for n in re.findall(r"\b(\d{2})\s", relation_text[1])] if relation_text else []
        body = re.sub(r"关联：[\s\S]*?(?=\n\n|$)", "", body)
        entries.append({"number": int(number), "id": key, "name": name,
                        "kind": BASE_KINDS[int(number)], "body": body.strip(), "related_numbers": related_numbers})
    assert len(entries) == 18
    sources = json.loads((AUTHOR / "catalog/sources.json").read_text())
    for filename, kind in (("arts", "spell"), ("items", "artifact"), ("talismans", "talisman"),
                           ("medicines", "pill"), ("materials", "material"), ("creatures", "creature"),
                           ("places", "setting")):
        for e in json.loads((AUTHOR / "catalog" / f"{filename}.json").read_text()):
            actual_kind = "practice" if e["id"] in PRACTICES else "item" if e["id"] in TOOLS else kind
            labels = [("definition", "定义与用途"), ("conditions", "条件"), ("effect", "作用"),
                      ("cost_end", "代价、持续与结束"), ("limits", "限制与应对"), ("example", "例子")]
            if kind == "creature":
                labels = [("definition", "形貌与定位"), ("conditions", "环境与接近"),
                          ("effect", "行为与能力"), ("cost_end", "生态变化与获得"),
                          ("limits", "应对与边界"), ("example", "遭遇例")]
            paragraphs = [f"### {label}\n\n{e[field]}" for field, label in labels]
            links = "、".join(f'[{sources[s]["title"]}]({sources[s]["url"]})' for s in e["sources"])
            paragraphs += [f'### 来源依据\n\n{e["basis"]} {links}', f'### 改编边界\n\n{e["adaptation"]}']
            entries.append({"number": e["number"], "id": e["id"], "name": e["name"],
                            "kind": actual_kind, "body": "\n\n".join(paragraphs)})
    assert len(entries) == len({e["id"] for e in entries}) == 100
    realm_sources = json.loads((AUTHOR / "catalog/realm-sources.json").read_text())
    for e in json.loads((AUTHOR / "catalog/realms.json").read_text()):
        labels = [("definition", "定义"), ("practice", "修习方式"), ("preparation", "突破准备"),
                  ("abilities", "阶段所得"), ("limits", "限制与风险")]
        if e["kind"] == "rule":
            labels = [("definition", "定义"), ("practice", "筹备与演练"), ("preparation", "进入条件"),
                      ("abilities", "过程与结果"), ("limits", "限制与风险")]
        paragraphs = [f"### {label}\n\n{e[field]}" for field, label in labels]
        citations = "；".join(f'[{realm_sources[s]["title"]}]({realm_sources[s]["url"]})' for s in e["sources"])
        paragraphs += [f'### 出处依据\n\n{e["basis"]}\n\n{citations}', f'### 本库改编\n\n{e["adaptation"]}']
        entries.append({"number": e["number"], "id": e["id"], "name": e["name"],
                        "kind": e["kind"], "body": "\n\n".join(paragraphs)})
    assert len(entries) == len({e["id"] for e in entries}) == 107
    return sorted(entries, key=lambda e: e["number"])


def reading_plan(entries):
    """校验作者声明的阅读顺序和引用目标；不从名称或正文推断进阶条件。"""
    plan = json.loads((AUTHOR / "catalog/realm-path.json").read_text())
    by_id = {e["id"]: e for e in entries}
    stages = plan["stages"]
    assert len(stages) == len(set(stages)) == 8
    assert all(by_id[key]["kind"] == "realm" for key in stages)
    assert by_id[plan["process"]]["kind"] == "rule"
    assert plan["process"] not in stages
    for key, links in plan["connections"].items():
        assert key in by_id
        assert len({link["target"] for link in links}) == len(links)
        for link in links:
            assert link["target"] in by_id, link["target"]
            assert link["label"] and link["note"]
    for key, targets in plan["backlinks"].items():
        assert key in by_id and all(target in stages for target in targets)
    return plan, by_id


def build():
    entries = source_entries()
    for e in entries:
        e["key"] = PREFIX + e["id"]
        e["resource"] = RESOURCE.get(e["kind"], "rules")
        e["url"] = PAGE[e["resource"]] + e["key"]
    plan, by_id = reading_plan(entries)
    # 保持原百条的自动阅读链接稳定；新境界只使用作者明确声明的关系。
    names = sorted((e for e in entries if e["number"] <= 100), key=lambda e: len(e["name"]), reverse=True)
    records = [
        row("publisher", "loreforge-xianxia", name="开物修仙资料编写"),
        row("gamesystem", "xianxia", name="凡人流修仙", content_prefix="xianxia",
            desc="以经典仙侠为参考的原创修仙资料定义；境界、术法与物品用于查阅，不自动执行游戏结算。"),
        row("document", DOCUMENT, name="凡人流修仙资料", display_name="凡人流修仙资料",
            type="MISC", publisher="loreforge-xianxia", gamesystem="xianxia", licenses=[],
            author="开物内容编写；参考作品及独立改编范围逐条标注", publication_date=None,
            permalink=None, distance_unit=None, weight_unit=None,
            desc=f"本来源包含{len(entries)}条通用修仙内容，参考《凡人修仙传》及古典小说、历史典籍，并标明本版独立补写的范围。\n\n"
                 "法术和物品使用原生资料模型；境界、功法、妖兽说明等使用独立规则条目。"
                 "5e环阶、伤害骰、生命值与货币单位没有被替换为修仙概念，不适用值保留为空。"
                 "条目仅提供定义、出处与相关资料，不扣除角色资源或裁决游戏结果。\n\n"
                 "来源链接可用于核对实际参考段落，条目正文不是小说全文或作者原规则的完整转录。"
                 "编辑数值和生态等补充属于本版内容；尚未另行声明本版发布许可。青禾坊人物、库存和事件未纳入本来源。"),
    ]
    rule_kinds = sorted({e["kind"] for e in entries if e["resource"] == "rules"})
    for kind in rule_kinds:
        desc = f"凡人流修仙资料中的{LABELS[kind]}。具体定义、适用边界与出处分别见各条。"
        if kind == "realm":
            line = " → ".join(f'[{by_id[key]["name"]}]({by_id[key]["url"]})' for key in plan["stages"])
            process = by_id[plan["process"]]
            desc = f"本库按以下八个境界组织修行主线，顺序用于资料查阅，不是自动升级规则。\n\n{line}\n\n大乘之后另见[{process['name']}]({process['url']})。它作为独立过程说明收录于修行通则，不列为第九个普通境界。"
        records.append(row("ruleset", PREFIX + kind, name=LABELS[kind], content_kind=kind,
                           document=DOCUMENT, desc=desc))
    for kind in sorted({e["kind"] for e in entries if e["resource"] in ("items", "magicitems")}):
        records.append(row("itemcategory", PREFIX + kind, name=LABELS[kind], content_kind=kind, document=DOCUMENT))
    for e in entries:
        # 名称匹配仅生成查阅链接，不推断施法能力、前置资格或运行事实。
        explicit = [other for other in entries if other["number"] in e.get("related_numbers", [])]
        mentions = [other for other in names if other["id"] != e["id"] and other["name"] in e["body"]]
        related = list({other["id"]: other for other in explicit + mentions}.values())[:8]
        if e["id"] in plan["connections"]:
            related = []
        body = e["body"]
        if related:
            body += "\n\n### 相关资料\n\n" + "\n".join(f'- [{o["name"]}]({o["url"]})' for o in related)
        if e["id"] in plan["stages"]:
            position = plan["stages"].index(e["id"])
            neighbors = []
            if position:
                previous = by_id[plan["stages"][position - 1]]
                neighbors.append(f'上一境界：[{previous["name"]}]({previous["url"]})。')
            if position < len(plan["stages"]) - 1:
                following = by_id[plan["stages"][position + 1]]
                neighbors.append(f'下一境界：[{following["name"]}]({following["url"]})。')
            else:
                process = by_id[plan["process"]]
                neighbors.append(f'后续过程：[{process["name"]}]({process["url"]})，不是第九个普通境界。')
            body += "\n\n### 境界衔接\n\n" + "\n\n".join(neighbors)
        if links := plan["connections"].get(e["id"]):
            body += "\n\n### 关联资料与用途\n\n" + "\n".join(
                f'- **{link["label"]}**：[{by_id[link["target"]]["name"]}]({by_id[link["target"]]["url"]})。{link["note"]}' for link in links)
        backlinks = [by_id[key] for key in plan["backlinks"].get(e["id"], []) if by_id[key]["url"] not in body]
        if backlinks:
            body += "\n\n### 相关境界\n\n" + "\n".join(f'- [{o["name"]}]({o["url"]})' for o in backlinks)
        fields = {"name": e["name"], "document": DOCUMENT, "desc": body}
        if e["resource"] == "rules":
            index = plan["stages"].index(e["id"]) + 1 if e["id"] in plan["stages"] else e["number"]
            fields.update(ruleset=PREFIX + e["kind"], index=index, initialHeaderLevel=2)
        elif e["resource"] == "spells":
            fields.update({key: None for key in (
                "level", "school", "higher_level", "range", "range_unit", "casting_time", "reaction_condition",
                "ritual", "concentration", "verbal", "somatic", "material", "material_specified",
                "material_cost", "material_consumed", "target_type", "target_count", "saving_throw_ability",
                "attack_roll", "damage_roll", "duration", "shape_type", "shape_size", "shape_size_unit")})
            fields.update(range_text="作用范围与条件见正文", damage_types=[], classes=[], applies_conditions=[])
        else:
            fields.update({key: None for key in ("size", "weight", "armor_class", "armor_detail", "hit_points",
                "hit_dice", "cost", "nonmagical_attack_resistance", "nonmagical_attack_immunity", "weapon", "armor")})
            fields.update(category=PREFIX + e["kind"], damage_vulnerabilities=[], damage_resistances=[], damage_immunities=[])
            if e["resource"] == "magicitems":
                fields.update(rarity=None, requires_attunement=None, attunement_detail=None, spells=[])
        records.append(row(MODEL[e["resource"]], e["key"], **fields))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "content.json").write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n")
    mapping = ["# 凡人流资料入库映射", "", "由 build_xianxia_fixture.py 从通用创作稿生成。青禾坊不在本批内。", "",
               "| 名称 | 分类 | 原生资源 | 稳定标识 |", "| --- | --- | --- | --- |"]
    mapping += [f'| {e["name"]} | {LABELS[e["kind"]]} | {e["resource"]} | {e["key"]} |' for e in entries]
    (OUTPUT / "mapping.md").write_text("\n".join(mapping) + "\n")
    print(json.dumps({"content": len(entries), "fixture_records": len(records),
                      "resources": dict(Counter(e["resource"] for e in entries)),
                      "kinds": dict(Counter(e["kind"] for e in entries))}, ensure_ascii=False))


if __name__ == "__main__":
    build()
