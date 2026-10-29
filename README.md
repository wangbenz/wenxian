# 问仙录

> 公开代码快照：2026-10-29。源码取自本地开发检查点 `fb250d8`，公开分支为 `codex/public-code-2026-10-29`。本次仅同步应用代码、运行资源、测试及必要运行说明；内部设计稿、营销素材、回放证据和未公开提交历史未随本次新增。保留的旧文档与素材来自此前已公开的父提交，不能当作本轮新设计或验收结论。
>
> Python 玩家运行时已包含在 `apps/game-runtime`，H5 在 `apps/player-h5`。出生至入道第一章和 D20 判定尚未接入当前主流程；运行时资料服务当前为独立的 `wenxianlu-library`，不能直接用本仓库 Loreforge/Open5e 服务替代。详细配置见 `apps/game-runtime/README.md`。快照不包含本地凭证、数据库或玩家存档；内部文档链接可能不在此公开快照内。

《问仙录》是一个响应式、对话驱动的单人修仙叙事应用。玩家通过推荐行动或自由输入推进命卷；服务端负责账号、模型路由、额度、权威事实、回合事务和长期存档，模型只生成结构化意图与叙事，不直接修改数据库。

当前推进 0.18 内部迭代的 Python 玩家后端。本地默认入口已切到 Python，正式环境尚未替换；管理台不在此次迁移范围。版本展示不能由 H5 或管理台写死。

## 项目结构

```text
wenxian/
├── apps/
│   ├── player-h5/       玩家端 React + TypeScript + Vite
│   ├── game-api/        游戏 Java 21 + Spring Boot 后端
│   ├── game-runtime/    Python 玩家后端与独立本地 SQLite
│   ├── admin-web/       管理台 React + TypeScript + Vite
│   └── admin-api/       管理台 Java 21 + Spring Boot 后端
├── database/
│   ├── migrations/      经 preflight 后按依赖选择执行的 MySQL 增量脚本
│   └── maintenance/     只读检查与需单独授权的受控维护资料
├── contracts/           跨应用共享的可执行契约与固定样例
├── docs/                当前文档、就绪度、日期证据与历史归档
├── deploy/              正式/灰度环境部署配置；旧配置进入 archive
├── marketing/           历史宣传素材，后续迁出源码仓库
├── AGENTS.md            开发和验证约束
└── README.md
```

各应用独立运行。Python 本地玩家库与原 Java/MySQL、资料库分开；原管理台不能代替游戏服务推进命卷或修改回合事实。

详细目录边界见 [项目结构说明](docs/project-structure.md)。

## 本地配置

本地玩家入口使用 Python 与独立 SQLite，服务器从环境变量 `DEEPSEEK_API_KEY`（或 `WENXIAN_MODEL_API_KEY`）读取凭证；未注入时只读取忽略文件 `apps/game-api/.env.local` 中的 `DEEPSEEK_API_KEY`，不执行该文件、不下发给 H5。

原 Java 后端使用 MySQL 8。复制 `apps/game-api/.env.example` 中需要的环境变量，或者在被 Git 忽略的
`apps/game-api/src/main/resources/application-secret.yml` 中配置本地数据库和模型密钥。该文件不会进入 JAR，也不能提交。

服务器密钥必须通过环境变量或外置 Spring 配置加载，不能下发给 H5 或管理台前端。

## 本地运行

先保持独立资料库 `http://127.0.0.1:8011/api/library/v1/` 可用。本地玩家入口一键启动：

```bash
cd apps/player-h5
npm ci
npm run dev
```

直接打开 **http://127.0.0.1:4189/**，无需 `?runtime=`。启动器同时运行 Python `18094` 与原 H5 `4189`；调用真实 DeepSeek，默认不设总调用次数限制。独立账号和命卷长期保存在 `apps/game-runtime/var/local-player/`，重启保留；旧 Java 账号与先前模拟库未导入，需要在本地注册账号。测试配置首次开卷赠 1000 点、每回 1 点，只用于本地联调。

停止使用 Ctrl-C；重新运行同一命令即可。资料库、密钥或端口不就绪会明确停止，不自动回退。详细配置、备份和验收见 [运行时说明](apps/game-runtime/README.md)。

需要显式回看 Java 时，先停止本地玩家启动器，再分别启动原后端和旧前端：

```bash
cd apps/game-api
mvn spring-boot:run
# 另一个终端，回到仓库根目录
cd apps/player-h5
npm run dev:legacy -- --host 127.0.0.1 --port 4189
```

该入口使用原 `18080` 和原 MySQL；不会读取或改写 Python 本地命卷。正式 H5 构建仍使用原配置，本次只替换本地默认启动。

管理台后端，默认端口 `18081`：

```bash
cd apps/admin-api
mvn spring-boot:run
```

管理台前端，默认端口 `4174`：

```bash
cd apps/admin-web
npm ci
npm run dev
```

## 验证

```bash
cd apps/game-runtime
uv sync --locked
uv run pytest
uv run ruff check src tests scripts/run_local_player.py
cd ../player-h5
npm run test:local
npm run build
```

`test:local` 是默认首页的离线交互工程检查；真实 DeepSeek 及重启恢复的实际结果另见 [本地替换验收](docs/evidence/runtime/2026-10-24-local-player-cutover.md)。原 Java 有改动时仍按其模块要求使用 Java 21 运行 `mvn test`。

确定性测试通过后，核心回合或模型路由变更还必须使用受控真实模型验证；没有真实模型回放，不能声称叙事链已经完成。

## 数据库迁移

`schema.sql` 只负责新环境基础建表，不能替代既有 RDS 的增量迁移。共享数据库变更必须先按
[`database/migrations`](database/migrations/README.md) 完成目标库 preflight，再根据真实结构和依赖选择脚本；禁止按文件名排序后全量执行。

## 核心文档

- [文档索引与状态说明](docs/README.md)
- [业务需求](docs/business-requirements.md)
- [产品方向](docs/product-direction.md)
- [玩法设计](docs/game-design.md)
- [系统架构](docs/architecture.md)
- [玩家核心流程](docs/player-core-flow.md)
- [核心就绪度与风险](docs/core-readiness.md)
- [可信回合协议](docs/trusted-turn-protocol.md)
- [数据字典](docs/data-dictionary.md)
- [版本记录](docs/version-history.md)
