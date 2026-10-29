# 开物 · Open5e 只读裁剪版

此目录基于 `open5e/open5e-api` 的固定提交 `0acbf263c74caf8ad080af7a16d86d3700a1ac8f`，保留上游 Django 模型、serializer、读取视图和完整历史迁移，追加小数尺寸修复及首批修仙资料实际需要的空值和引用适配。原项目说明见 `UPSTREAM-README.md`，原许可证保留于本目录。没有复用此前移除的 Java 实现。

本轮只提供资料读取。内容通过明确选择的本地文件直接导入 SQLite，没有登录、管理台、REST 写入、复杂版本发布或游戏运行态。V1、搜索/索引、Debug Toolbar 和 New Relic 未激活；相应上游代码仍可能保留以避免误删共享工具和历史依赖。

## 本地使用

在此目录运行（`uv` 可自动安装 Python 3.11）：

```sh
uv sync --locked
uv run python manage.py migrate --noinput
uv run python manage.py load_loreforge ../data/starter
uv run python manage.py load_loreforge ../data/cultivation
uv run python manage.py load_loreforge ../data/xianxia
uv run python manage.py runserver
```

默认监听 `127.0.0.1:18087`。数据库为本目录忽略的 `.data/loreforge.sqlite3`，与旧 MySQL、问仙录和玩家数据分开。开发服务器不是生产部署配置。停止服务使用所在终端的 Ctrl-C，不删除数据库。

公开入口均为原生 `/v2/` 路由：

- 内容：`spells/`、`items/`、`magicitems/`、`rules/`、`rulesets/`、`conditions/`。
- 来源：`documents/`、`publishers/`、`licenses/`、`gamesystems/`。
- 字典：`spellschools/`、`itemcategories/`、`itemrarities/`、`sizes/`、`damagetypes/`。

例如 `/v2/spells/?name__icontains=火` 与 `/v2/spells/<key>/`。另有联合目录 `/v2/catalog/`，参数见下方0085说明。状态和伤害类型的正文位于 `descriptions`。所有公开入口拒绝 POST/PUT/PATCH/DELETE。不开放职业、武器、防具等路由，但保留模型及其依赖；境界、功法、妖兽说明以分类规则条目承载。

CORS 只允许 `http://127.0.0.1:5178` 和 `http://localhost:5178` 的本地浏览前端。服务只返回 JSON，不加载上游艺术素材或管理表单。

## 资料文件与直接导入

`load_loreforge` 只接受 `loreforge/data` 内已有目录，读取该目录第一层的 `*.json`。这些文件必须为标准 Django fixture 数组：

```json
[
  {"model":"api_v2.publisher","pk":"example","fields":{"name":"示例署名"}}
]
```

字段和外键使用上游定义，publication_date 沿用上游 USE_TZ=False，不为原始无时区日期补造时区。主键通常是稳定字符串；施法变体使用明确正整数主键。可导入法术/变体、两类物品、规则/章节、状态/伤害类型及各自说明、来源与实际使用的字典。没有开放职业、妖兽、图片或通用任意模型写入。

整批进入一个数据库事务，允许同批前向引用，使用 Django 原生字段校验并检查外键与多对多关系。任一未知字段、非法选项、重复批内主键或悬空引用都会回滚。数据库允许的 NULL 不强行填造成数字；模型明确声明的空串默认值（例如 armor_detail）原样保留，不因 Django 的表单 blank 约束强迫补写内容。无此默认值的必填名称等字段仍严格校验。相同主键重导更新该定义，文件中明确给出的关系数组整体替换；未列出的记录不删除。请将文件作为完整内容定义维护，未提供的普通字段遵循上游构造默认值，不能当作局部 PATCH 使用。

本地命令不代表免除内容审核。模型的 D&D 等级、学派、单位、同调等含义保留；中文改编及来源/许可说明由资料本身明确，不把字段改名当作完整玩法转换。

不要执行原 `quicksetup`、全量 `import`、`export` 或建索引命令。本轮新增入口只有受限 `load_loreforge`；原代码的高级导入导出并未作为本轮支持路径验证。

## 验证

```sh
uv run python manage.py check
uv run pytest -q
```

默认执行 `api_v2/tests/test_loreforge.py`、`test_loreforge_cultivation.py` 和 `test_loreforge_catalog.py`，使用 Django 的隔离测试SQLite。覆盖三批资料、重复导入、事务回滚、引用、中文筛选、只读路由、旧值保留及0083/0084/0085迁移边界。上游依赖全量资料或远端服务的测试保留为参考，未声称已经运行通过。

本轮使用 Python 3.11.15 验证，项目要求 Python 3.11；Django 5.2.14、DRF 3.17.1 和完整依赖见 `uv.lock`。保留模型和迁移意味着库内仍有未启用内容类型的空表，不意味着相应功能已经开放。

## 已确认的最小模型修复

`Size.space_diameter` 原本使用整数距离字段，但上游 core 中 Tiny 的原值是 2.5。最初裁剪仅将这一列改为保留小数的 FloatField，并通过新增 `0083_preserve_fractional_size_diameter` 迁移升级；原有非负、可空和可留空约束保留，JSON API 仍返回数字。没有修改全局 distance_field 或把源资料改成整数。后续0084改动见下节；上游历史迁移保持原样。

对应回归覆盖资料导入、数据库读取以及 `/v2/sizes/` 返回 2.5。导入解析另外明确拒绝非标准 JSON 常量 NaN、Infinity 与 -Infinity。

## 首批修仙资料的最小适配

新增迁移 `0084_cultivation_content_references` 保留旧记录原值，仅增加以下能力：

- `Spell.level/school/casting_time/attack_roll/concentration/ritual` 可为 `null`；`higher_level/saving_throw_ability/damage_roll` 也允许 `null` 表示尚未定义或不适用。既有空串含义保留，例如明确没有材料要求的 `material_specified=""`。有值时仍执行原等级范围、选项、外键等校验，不将境界伪装成环阶。
- 仅在 `MagicItem` 覆盖 `size/weight/armor_class/hit_points/nonmagical_attack_resistance/nonmagical_attack_immunity/requires_attunement` 的可空性，不改变 `Object` 及普通物品、生物的字段约束。`cost/armor_detail/hit_dice/attunement_detail/rarity` 原已支持空值。旧构造默认值保留；新资料须显式写 `null`，避免把省略误当作未知。非空售价、重量、战斗数值仍执行原数值校验。
- `Document.publication_date/permalink` 支持 `null/blank`，可表达未知日期和尚未发布的网址。不会自动填写日期、网址或许可证。
- `Spell.applies_conditions` 引用 `Condition`，`MagicItem.spells` 引用 `Spell`。fixture 使用稳定主键数组；列表和详情 API 均返回只读 `[{"key":"…","name":"…"}]`，无关系返回 `[]`，不递归加载目标全文。导入器沿用现有存在性校验与整批事务，关系数组明确为空会清空该关系，未提供关系字段会保留原关系；悬空引用会撤销整批写入。

这些关系记录资料中的声明，不能施加游戏状态、执行施法、计算消耗或推进时间。HTTP 入口保持只读，删除引用目标的请求同样返回 405；本地导入不提供删除操作。模型内部仍沿用 Django 的关联删除行为，不能把这套读取接口理解为已经实现受控内容删除服务。

独立来源为 `gamesystem=cultivation`、`document=loreforge-pilot`。法术、器物列表可使用 `document__key__in=loreforge-pilot` 或 `document__gamesystem__key__in=cultivation`。状态增加 `source_document=<document-key>`，按状态说明的来源筛选；原 `document__key` 继续按状态身份来源筛选。原 `invisible` 的身份仍属于 `core`、说明属于 `srd-2024`，因此 `source_document=srd-2024` 能正确找到它。

0084 升级不回填或改写旧资料，新增关系初始为空。未使用新增空值和关系时可以反向迁移；一旦存在新增空值或引用，反向迁移会明确拒绝，防止默认值填充或删除关系造成信息丢失。导入后的回滚应恢复升级前的独立 SQLite 备份；不使用清空资料或补造 0/false 的方式强行降级。

## 通用修仙目录与0085

2026-09-28阅读改进：目录增加 `excerpt`，从数据库中的正文前600字符提取首个正文段落，最多140字符及省略号，仅用于查阅辨认，不是模型生成摘要；完整正文不在目录中传输。`ItemCategorySummary` 增加数据库已有的 `content_kind`，供阅读页显示明确分类。这两项为只读协议扩展，无表结构变化。

`data/xianxia` 为107条正文及13条依赖，复用Spell、MagicItem、Item和Rule四类原生模型。联合只读接口 `/v2/catalog/` 在SQL中筛选、排序、分页，返回索引、受限正文摘录与原生详情入口；完整正文按需读取。支持 `name__icontains`（或 `search`）、`kind`、`document__key__in`、`ordering=name/-name/key/-key`；默认每页50，`limit`最多100。分类明细与例子见 [资料说明](../data/xianxia/README.md)。

- 新增 `RuleSet.content_kind` 和 `ItemCategory.content_kind`（带索引），分别默认 `rule`、`item`，不修改旧正文。Rule读取结果增加 `content_kind/ruleset_name`，document返回来源摘要；规则详情沿用 `/v2/rules/<key>/`。`ruleset__content_kind` 可用于原规则接口分类筛选。
- `Item.size/weight/armor_class/hit_points/nonmagical_attack_resistance/nonmagical_attack_immunity` 支持NULL；不改变Object和Creature模型。
- `Spell.range_text/duration/verbal/somatic/material/material_specified/material_consumed` 支持NULL；有值时保留原校验，旧默认值与实际记录不回填。
- 新数据以 `xianxia-core` 独立来源导入。器物与法术仅相关时生成阅读链接，不写入“可施法”关系。

0085只有在未使用新增分类和空值时可反向迁移；已有使用会在删除字段前拒绝。导入前备份独立SQLite，导入后的整体回退恢复备份与匹配代码。不得借此操作共享库或玩家状态。

## 境界顺序与相邻阅读（2026-09-29）

`/v2/catalog/?kind=realm&document__key__in=xianxia-core&ordering=sequence` 按来源、Rule.index及稳定标识排列；`sequence` 是已有章节次序，非规则类资料返回null，不表示战力等级。此排序要求kind=realm，其他分类请求返回400。Rule详情增加只读 `reading_neighbors`：仅境界返回previous/next的key与name，非境界为null。查询按同ruleset、同document隔离，按index/key排序，每请求只缓存名称索引；不装载其他条目全文。大乘没有下一境界，正文另链接到修行通则内的渡劫飞升过程。沿用0085数据库结构，无新增迁移。
