# 凡人流修仙资料

107条通用内容已经转换为Open5e原生模型可导入的fixture，独立来源为 `xianxia-core`，设定体系为 `xianxia`。青禾坊人物、库存与事件不在此批次。此批包含八个境界（炼气至大乘）与单列的渡劫飞升过程。正文中的出处依据与本库改编分别标明。

| 分类 | 数量 | 原生模型 |
| --- | ---: | --- |
| 法术 | 16 | Spell |
| 法器、符箓、丹药 | 12 / 9 / 6 | MagicItem |
| 灵材、工具 | 15 / 2 | Item |
| 境界、功法、妖兽、场所与社会、通则 | 8 / 4 / 15 / 13 / 7 | Rule / RuleSet |

`content.json` 共120条fixture记录：107条正文及13条来源、体系和分类依赖。数量不含旧SRD与修仙试录。逐条稳定标识见 [mapping.md](mapping.md)。妖兽使用图鉴正文，不填造5e生命值或挑战等级。

## 维护与导入

编辑来源是 [18条基础正文](../../docs/cultivation-source-design-2026-09-27/content-cards.md) 与 `catalog/` 下七份原JSON分册、`sources.json`，以及新增的 `realms.json`、`realm-sources.json`、`realm-path.json`。条目包含定义、条件、效果、限制、例子、出处和独立改编范围。基础正文的显式关联以及名称提及转换为站内阅读链接，不推断器物可施法能力。

在 `loreforge/` 运行：

```sh
python3 scripts/build_xianxia_fixture.py
cd api
uv run python manage.py migrate --noinput
uv run python manage.py load_loreforge ../data/xianxia
```

导入前备份独立SQLite。相同稳定主键重导会更新；整批非法字段或悬空引用会回滚，未列出的旧资料保留。后续由作者批量维护这些源文件并重新生成、验证、导入，不需要用户逐卡维护。游戏角色、库存与剧情不在这个资料库中。

## 读取

- 网页：`http://127.0.0.1:5178/library`，默认选择本来源，支持名称搜索、11类筛选、分页、详情与关联跳转。
- API：`/v2/catalog/?document__key__in=xianxia-core`，返回107条的分页索引；每条给出 `page_url` 和 `api_url`。
- 分类示例：`/v2/catalog/?document__key__in=xianxia-core&kind=realm&ordering=sequence`。
- 单条示例：`/v2/rules/xianxia_realm-foundation/`、`/v2/spells/xianxia_spell-fire-bolt/`。

目录读取索引与受限正文摘录，完整正文按原生详情接口加载。`limit` 每页最多100，默认50；网页每页25。目录支持 `name__icontains`（或 `search`）、`kind`、`document__key__in`、`ordering=name/-name/key/-key`；境界另支持 `ordering=sequence`（各来源内按章节顺序），排序有稳定主键作为次序补充。所有HTTP入口只读。

## 数据与回退

0085新增 `RuleSet.content_kind`、`ItemCategory.content_kind`，旧值分别默认 `rule`、`item`；新增适用空值字段见 [API说明](../../api/README.md#通用修仙目录与0085)。原字段含义保持，不把境界塞进法术环阶，不把未知价格写成0。

0085未使用新增语义时可以无损反向迁移；已有新分类或NULL时明确拒绝降级。此时回滚应先停止本地API，保留当前库，再恢复升级前SQLite备份并配合旧代码，不能强填0/false或清库。没有更改共享数据库。

## 本地验收（2026-09-28）

- Python 3.11：27个测试、200个子测试通过，涵盖100条详情、11类筛选、分页、重导、非法分类整批回滚、旧资料保留、0084/0085正反迁移。
- Node 22.23.3：类型检查、构建、28个既有前端测试与9个实际HTML服务端渲染检查通过。
- 本地SQLite升级并导入113条记录；升级前115行旧数据按原有全部字段比对保留，数据库完整性检查通过。备份留在忽略的 `api/.data/` 内。
- 真实浏览器验证名称搜索、分类、空结果、翻页、详情往返保留筛选/页码、图鉴直接刷新；390px窄屏目录正常，无横向溢出。修复目录面包屑首屏告警与无效中间链接后，最终构建未再产生新的初始化告警。

这是本地可用资料库的验收，尚未部署，也不代表战斗平衡或游戏连续试玩通过。

## 境界主线补齐（2026-09-29）

新增结丹、元婴、化神、炼虚、合体、大乘与渡劫飞升，合计107条内容。八境界按章节顺序浏览，单条境界返回同来源、同章节的 `reading_neighbors`；它们是阅读入口，不是角色升级条件。无表结构变动。来源登记见[本次核对](../../docs/cultivation-source-design-2026-09-27/catalog/realm-sources.md)。
