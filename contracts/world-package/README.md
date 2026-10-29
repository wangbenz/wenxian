# 世界包 Bundle v1 合同

本目录是 `.wenxian-world` 首版文件结构的机器可读合同。Schema 描述各文件应满足的结构；当前 game-api
摄取管线使用严格 Jackson 解析与 `WorldPackageValidator` 完成字段、跨文件引用、成长轨道、创角入口、能力
和资源限制校验，尚未接入执行这些 JSON Schema 的验证引擎。不能把两套定义已存在表述为运行时已依次执行。
后续协议收敛需增加 Schema 与 Java 的一致性用例；外部导入开放前明确统一的结构校验入口。

| Bundle 文件 | Schema |
|---|---|
| `manifest.json` | `manifest-v1.schema.json` |
| `world.json` | `world-v1.schema.json` |
| `creation.json` | `creation-v1.schema.json` |
| `progression.json` | `progression-v1.schema.json` |
| Lore 索引 | `lore-index-v1.schema.json` |
| Lore 内容文件 | `lore-file-v1.schema.json` |
| `checksums.json` | `checksums-v1.schema.json` |

所有 Schema 使用 JSON Schema Draft 2020-12，并关闭未知字段。Schema 只表达单文件结构；以下约束由 Java
语义校验器负责：

- 稳定 ID 在包内全局唯一，引用存在且对象类型相符；
- v1 Bundle 自包含，不允许跨包引用；
- 可玩种族必须且只能绑定一条线性成长轨道；
- 同一成长体系的各轨道覆盖相同比较层级；
- 默认开场属于可开场地域，场景种子只能引用可开场地域；
- Lore 故事线触发只能引用当前包场景种子；
- 内容能力、大小、总量、校验和和素材签名满足运行时限制。

官方完整样例位于 `apps/game-api/src/main/resources/world-packages/official/XUANXU/2/`，异构测试样例位于
`apps/game-api/src/test/resources/world-packages/fixtures/DREAMSEA/1/`。阶段 B 已通过统一发布服务接入数据库注册表；生产运行时从注册表读取，经临时 v2 适配器提供现有目录。尚未部署，命卷 v3、动态境界与玩家导入导出仍待后续阶段。素材原件与修订同事务保存，但不直接提供浏览器渲染。

当前产品只开放官方玄墟界；玩家包创作与交换延期。注册表读取已完成本地修正，公开背景资料的回合请求已接通本地代码；后续贯通地点、人物与世界知识的连续游玩，
见 [平台设计](../../docs/world-package-platform-design.md) 和 [连续性设计](../../docs/story-world-continuity-design.md)。
本批回合接入只支持可用于叙事的公开世界背景；现有玄墟界 Lore 的修行传承和多族秩序属于该范围。v1 没有可见性或发现条件字段，不能承载需要对正文保密的隐藏身份、幕后真相；任意候选是否满足公共资料范围仍需内容审查，不能从标签、优先级、来源或激活命中自动推断。秘密资料需要先设计可见性及导演结果到正文的披露协议，不能仅靠 Prompt 保密。

世界资料以带来源的低信任数据分区进入模型请求，不能成为系统指令，不能覆盖命卷已确认事实；相关性命中也不表示人物已经知道或应立即揭示。详见平台设计第 19.5 节。本批不修改 Schema 或既有 Bundle 内容/hash。
