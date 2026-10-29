# 开物首批修仙试录资料

依据 [2026-09-27 改编草案](../../docs/cultivation-pilot.md)制作的四项实际资料。仅供本地查阅与选材，未接入游戏、未验证战斗平衡，不代表所有修仙世界的通则。`cultivation.json` 为标准 Django fixture，共 **9 条记录**，可以独立导入，不依赖或覆盖 `starter`。

## 内容与关系

| 核心资料 | 模型 / 稳定主键 | 结构化关系 |
| --- | --- | --- |
| 隐身 | `condition / loreforge_invisible` | 说明存于 `conditiondescription / loreforge-pilot_invisible` |
| 隐身术 | `spell / loreforge_invisibility` | `applies_conditions` 指向 `loreforge_invisible` |
| 探灵术 | `spell / loreforge_detect-spiritual-effects` | 由探灵杖引用的同一能力定义 |
| 探灵杖 | `magicitem / loreforge_detection-wand` | `spells` 指向 `loreforge_detect-spiritual-effects` |

其余4条为 `publisher / loreforge`、`gamesystem / cultivation`（修仙示范设定，前缀 `lf`）、`document / loreforge-pilot`（开物修仙试录）及 `itemcategory / loreforge_wand`。所有外键和关系在本批闭合。正文同时提供相应详情页的 Markdown 链接；隐身术不另写状态能力，探灵杖不另写探灵术能力。

关系用于资料查阅，不负责自动施加状态、扣减次数、时间推进、随机恢复或游戏裁决。器物专属次数仍按草案写在正文，没有扩展为全局灵力或境界规则。

## 未定义值

- `null` 表示本条未定义或不适用的5e字段，不是0、免费、无需维持或已经获得资格。两法术的 `level`、`school`、`casting_time`、`higher_level`、`attack_roll`、`saving_throw_ability`、`damage_roll` 均为 `null`。
- 隐身术的 `concentration`、`ritual` 为 `null`；探灵术的这两项为 `false`，因为草案明确移除了持续专注与仪式施法。两法术已明确诵咒与手势、无需材料，因此 `verbal`/`somatic=true`、`material`/`material_consumed=false`，`material_specified` 为空串。
- 自身、接触和单目标是已明确的限制；数值距离与单位保留 `null`，不造一个0距离。持续时间分别为 `up to 1 hour` 与 `instantaneous`，具体结束条件以正文为准。
- 探灵杖的价格、重量、尺寸、品阶、同调及物品战斗数值均为 `null`。`classes`、伤害类型与抗性等空数组只表示本批没有声明对应关系，不补成职业资格或物品防御规则。
- 来源的出版日期、永久链接及默认计量单位为 `null`；本地试录日期不冒充出版日期。

## 来源和本次修改

原材料为 Wizards of the Coast 的 **System Reference Document 5.2**，采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)（[许可全文](https://creativecommons.org/licenses/by/4.0/legalcode)），见[原 SRD 入口](https://dnd.wizards.com/resources/systems-reference-document)。数据整理取自 [Open5e 固定提交 `0acbf263c74caf8ad080af7a16d86d3700a1ac8f`](https://github.com/open5e/open5e-api/tree/0acbf263c74caf8ad080af7a16d86d3700a1ac8f/data/v2/wizards-of-the-coast/srd-2024)，沿接本地 [SRD 中文试录](../starter/README.md)已有中文名称与导读记录。

| 原材料 | 本次改编 |
| --- | --- |
| Invisible | 去掉优势/劣势，明确非视觉线索和携带物边界，结束条件由施加者定义。 |
| Invisibility | 自身单目标，攻击或施法开始时结束；移除材料、职业、法术位与升环规则，维持代价待定。 |
| Detect Magic | 接触一件非生物物品，仅判断正在生效的术法有无；取消范围扫描、学派识别、仪式与持续专注。 |
| Wand of Magic Detection | 保留3次容量和黎明恢复1至3次，调用本批探灵术，明确手势/诵咒和启动失败、阴性结果的消耗区别。 |

本次改编使用独立主键和独立来源；原 `srd-2024_*`、`invisible` 及其英文正文保持原样。`Document.licenses=[]` 表示本次改编贡献尚未公布许可；原 SRD 材料的 CC BY 4.0 署名和链接保留在来源正文与这里，不借此新增许可承诺。每项正文均有改编说明和来源链接，不冒充 Wizards of the Coast 原文。

## 本地导入

在完成本轮后端迁移的本地环境中，从 `loreforge/api` 执行：

```sh
uv run python manage.py load_loreforge ../data/cultivation
```

同主键重导更新本批定义与显式关系，未列出的资料不删除。与 SRD 试录可以并存；内容同名时应通过“开物修仙试录”来源辨别。

制作时静态核对 JSON、重复身份、与 starter 的身份隔离、全部外键/多对多及正文内部链接。该静态核对不等同于数据库导入、HTTP 读取或游戏执行验证。
