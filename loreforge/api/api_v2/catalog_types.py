"""只读资料目录的分类；只用于检索，不判定游戏行为或角色资格。"""

CATALOG_KINDS = {
    "realm": "境界", "practice": "功法", "spell": "法术",
    "artifact": "法器", "talisman": "符箓", "pill": "丹药",
    "material": "灵材", "creature": "妖兽", "setting": "场所与社会",
    "rule": "规则", "item": "物品",
}
RULE_KINDS = [(key, CATALOG_KINDS[key]) for key in ("rule", "realm", "practice", "creature", "setting")]
ITEM_KINDS = [(key, CATALOG_KINDS[key]) for key in ("item", "artifact", "talisman", "pill", "material")]
