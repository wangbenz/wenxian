# 开物首批 SRD 中文试录资料

本目录用于开发者直接导入资料、验证分类读取和详情展示。**中文试录，沿用 SRD 原规则，非完成修仙规则改编。** 它不是正式的 12 条修仙基础内容，也不使用旧版 `mvp-release.json` 结构。

`starter.json` 是标准 Django fixture 数组，每条恰好包含 `model`、`pk`、`fields`。共 **48 条记录：7 项核心资料、18 项施法变体和 23 项必要依赖**。没有职业、图片、武器、护甲、功法、境界或秘籍记录；没有导入整套规则书。

## 核心资料与稳定身份

| 中文试录名 | 原名 | 模型 | 保留的原主键 |
| --- | --- | --- | --- |
| 火弹术 | Fire Bolt | api_v2.spell | srd-2024_fire-bolt |
| 疗伤术 | Cure Wounds | api_v2.spell | srd-2024_cure-wounds |
| 探灵术 | Detect Magic | api_v2.spell | srd-2024_detect-magic |
| 隐身术 | Invisibility | api_v2.spell | srd-2024_invisibility |
| 疗伤丹 | Potion of Healing | api_v2.item | srd-2024_potion-of-healing |
| 探灵杖 | Wand of Magic Detection | api_v2.magicitem | srd-2024_wand-of-magic-detection |
| 隐身 | Invisible | api_v2.condition | invisible |

状态的完整说明另存于 `api_v2.conditiondescription / srd-2024_invisible`。疗伤丹只是本批中文名称；原资料仍描述魔法药水，没有把液体、服用动作或剂量机制改成修仙丹药规则。`Item` 与 `MagicItem` 是上游资源划分，不等同于“凡物”和“法宝”。

## 来源与许可

- 上游仓库：[Open5e API](https://github.com/open5e/open5e-api)。本批固定于提交 [`0acbf263c74caf8ad080af7a16d86d3700a1ac8f`](https://github.com/open5e/open5e-api/tree/0acbf263c74caf8ad080af7a16d86d3700a1ac8f)，不是当前线上数据的声明。
- 四法术、两物品及状态/伤害说明来自 [`data/v2/wizards-of-the-coast/srd-2024`](https://github.com/open5e/open5e-api/tree/0acbf263c74caf8ad080af7a16d86d3700a1ac8f/data/v2/wizards-of-the-coast/srd-2024)，其 Document 名称为 **System Reference Document 5.2**，作者和发布方为 **Wizards of the Coast**，规则体系为 `5e-2024`。
- 必要学派、物品类别、尺寸、稀有度、状态本体和伤害类型来自 [`data/v2/open5e/core`](https://github.com/open5e/open5e-api/tree/0acbf263c74caf8ad080af7a16d86d3700a1ac8f/data/v2/open5e/core)。它们具有自己的 `core` 来源，不能将其来源强行改成 `srd-2024`。
- 原 SRD 入口：[Systems Reference Document](https://dnd.wizards.com/resources/systems-reference-document)。Document 的作者、名称、原网址、发布时间和默认单位均按该提交中的 fixture 保留；没有重新核定或伪造出版日期。
- SRD 5.2 内容采用 [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/)，许可正文见 [CC BY 4.0 legal code](https://creativecommons.org/licenses/by/4.0/legalcode)。本批保留对 Wizards of the Coast、原 SRD 与 Open5e 数据整理来源的署名，并在这里和条目正文明确标记修改。
- `core` 的上游 Document 同时列出 `cc-by-40` 与 `ogl-10a`；本批照原元数据保留两项关联，且 `License.json` 中两份许可全文均包含在 fixture 内。未将代码仓库的 MIT 许可当作资料许可，也未推定图片遵循同一许可。

英文归属说明：This starter includes material from the System Reference Document 5.2 by Wizards of the Coast, provided under the Creative Commons Attribution 4.0 International License, and core reference records curated by Open5e. Chinese display names and introductory notes were added for the Loreforge starter; the modifications are listed below. Original rule text remains included.

## 本批的有意修改

1. 七项核心资料的 `name` 使用上表中文试录名，**主键不改**。其他来源和字典名称保留原文。
2. 四法术、两物品和隐身状态说明的 `desc` 前加入中文试录标记、简短导读与原名。每份原英文 `desc` 仍逐字完整保留在“原英文正文（完整保留）”之后；没有翻译整书，也没有删去原施法条件、次数、恢复或结束条件。
3. 为不导入整套职业资料，四法术的结构化 `classes` 暂置为 `[]`。**各条正文保留原职业名称，原 key 列于下表**；空数组只表示本批结构裁剪，不表示原规则没有职业限制，也不代表任何角色已经取得施法资格。未来若导入职业资料，应以同一上游提交恢复原 M2M 关系。
4. `Condition / invisible` 的可空 `icon` 从 `elderberry_invisible` 改为 `null`，不导入 Image 或艺术文件，不复制或加载上游非商业艺术图片。本批其他核心资料没有新增图片引用。

除上述字段外，所选原记录的其他字段和数值保持原样，所有原施法变体保持原样。没有增添伤害到灵力、法术位到境界、职业到宗门的映射。

### 原职业引用（维护记录）

| 法术 | 原职业及标识 |
| --- | --- |
| `srd-2024_cure-wounds` | Bard (`srd-2024_bard`)；Cleric (`srd-2024_cleric`)；Druid (`srd-2024_druid`)；Paladin (`srd-2024_paladin`)；Ranger (`srd-2024_ranger`) |
| `srd-2024_detect-magic` | Bard (`srd-2024_bard`)；Cleric (`srd-2024_cleric`)；Druid (`srd-2024_druid`)；Paladin (`srd-2024_paladin`)；Ranger (`srd-2024_ranger`)；Sorcerer (`srd-2024_sorcerer`)；Warlock (`srd-2024_warlock`)；Wizard (`srd-2024_wizard`) |
| `srd-2024_fire-bolt` | Sorcerer (`srd-2024_sorcerer`)；Wizard (`srd-2024_wizard`) |
| `srd-2024_invisibility` | Bard (`srd-2024_bard`)；Sorcerer (`srd-2024_sorcerer`)；Warlock (`srd-2024_warlock`)；Wizard (`srd-2024_wizard`) |

这些原标识供数据维护和以后恢复关系使用，不在读者正文中解释内部字段或导入实现。

## 保留的字段语义与已知边界

- `school` 是原 5e 学派，使用 Abjuration、Divination、Evocation、Illusion 四条原学派资料；不是之前讨论的九种用途分类。
- `level` 保留原法术等级/法术位语义，0 为戏法；`higher_level` 保留完整原文。`player_level_N` 与 `slot_level_N` 分别表示原角色等级与法术位等级，不能互换。
- Fire Bolt 保留 3 个等级变体；Cure Wounds 保留 8 个升环变体；Detect Magic 没有独立变体，但主记录 `ritual=true` 保留；Invisibility 保留 7 个升环变体。Cure Wounds 的 `damage_roll` 实际承载完整治疗骰表达式，不能解释为伤害或额外治疗增量。
- Potion of Healing 的毒素/心灵伤害免疫描述物品自身，不授予饮用者免疫。Wand of Magic Detection 的 3 次充能、每次消耗与黎明恢复完整保留在正文。
- 原 `tiny.space_diameter=2.5` 保留，单位继承 `core.distance_unit=feet`。不能在导入时截为整数；后端应使用本轮已修正的 Size 小数尺寸字段。
- 原记录仍有需要阅读正文辨别的表达边界：Fire Bolt 的 `target_type` 为 `creature`，但正文允许 creature 或 object；Invisibility 的 `attack_roll` 为 `true`，不能据此推断施放它一定需要攻击检定。本批未静默纠正这些上游字段，也不宣称通过了规则引擎执行验证。
- 价格、重量、生命值及物品防御的原数值保留；它们不是已确认的修仙经济或战斗数值。正文中的其他原规则术语仍需按 SRD 理解。

## 依赖数量

| 模型 | 条数 |
| --- | ---: |
| publisher / license / gamesystem / document | 各 2 |
| spellschool | 4 |
| itemcategory | 2 |
| itemrarity / size | 各 1 |
| damagetype / damagetypedescription | 各 3 |
| condition / conditiondescription | 各 1 |
| spell / spellcastingoption | 4 / 18 |
| item / magicitem | 各 1 |
| **合计** | **48** |

记录在单一 fixture 中先放来源与字典，再放引用它们的内容和子项。类别、来源、两种物品、状态说明和伤害说明全部形成可单批导入的引用集合。

## 导入与静态检查范围

按后端 README 配置本地 Loreforge 环境后，在 `loreforge/api` 执行标准开发者导入命令：

```sh
uv run python manage.py load_loreforge ../data/starter
```

命令由后端实现，读取目录第一层 `*.json`，同主键重导会更新资料；未列出的记录不删除。本目录只将 `starter.json` 用作可导入 JSON，README 不参与导入。

资料制作时已静态核对：JSON 可解析；所有记录符合 fixture 外形；模型/主键组合无重复；必要 FK、M2M 及法术 damage_types 均在本批闭合；施法变体主键为原正整数；四法术原职业名称写入正文，原key留在维护记录；全部原英文正文保留；修改字段只属于上述清单。**这些是人工样本与文件静态检查，不是数据库导入、HTTP 或游戏规则执行测试。** 实际事务导入和读取验证由后端流程完成。

`starter.json`：43,053 字节；SHA-256：`0c38cc76f5ac1ff09786a4551fe30d90b6c243f7772c01d8f207e14ca8c46bd8`。
