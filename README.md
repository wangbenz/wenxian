# 问仙录

当前稳定试玩版为 V0.13；V0.2 正在 `V0.2` 分支和 `v2/` 目录独立开发。V0.13 线上玩法代码不在 V0.2 开发中原地重写。

响应式对话式修仙文字游戏 H5。世界观固定为「玄墟界」，玩家命名并选择种族后，由 DeepSeek V4 Pro 生成三种开局命格与 1—3 种可选先天天赋；之后通过快捷选择或自由输入推进自己的单人世界线。同一命卷保存连续 Agent 消息、统一世界状态和逐回合审计。

当前交互约定：角色、行囊、天赋、功法、纪事和世界页只展示服务器状态；所有会改变命卷的操作都在「历练」页输入。单回合以 800—1200 字完整章节为目标，并支持宣纸/月夜/跟随系统三种阅读外观与三档字号。无法成立或含义不清的自由输入会返回世界观内的明确原因和可行建议，不推进回合、时间或人物状态。

V0.2 将后端改为“权威领域状态 + 版本化内容 + 受约束叙事”的结构。当前已先完成独立后端和新表基线，尚未替换线上 H5。

长线命卷采用滚动上下文：数据库保留完整原文，模型每轮只接收权威摘要、当前状态和最近完整章节，避免游玩回合增加后请求持续变慢。模型等待阶段不占用数据库长事务，多名试玩用户可以同时推进不同存档。

## 项目结构

```text
wenxian/
├── frontend/   React + TypeScript + Vite 响应式 H5
├── backend/    Java 21 + Spring Boot 权威游戏服务
├── admin/      独立的 React + Spring Boot 内测管理台
├── v2/
│   └── backend/  V0.2 独立游戏后端与 Flyway 迁移
└── docs/       玩法与 Agent/数据架构
```

管理台与游戏服务共用同一个 MySQL `wenxian` 库。新生成的内测码会同时保存校验哈希和仅供管理员复制的明文；早期只保存哈希的旧码无法逆向恢复，管理台会明确标记为“明文不可恢复”。邀请码属于历史记录：已经使用、达到上限或过期的码仍会保留并显示为失效。清理操作只删除从未使用且没有明文记录的错误生成码，不删除正常的邀请码历史。

## 本地运行

V0.2 后端使用 Java 21、Spring Boot、MySQL 8 和 Flyway。它复用既有 `app_user`、`user_session`、灰度白名单与账号配置表，所有新玩法表使用 `v2_` 前缀；AI 供应商配置使用中立表名。

验证 V0.2 后端：

```bash
cd v2/backend
mvn test
```

启动时通过 `GAME_DATABASE_URL`、`GAME_DATABASE_USERNAME`、`GAME_DATABASE_PASSWORD` 连接测试库，默认监听 `18180`。本地私密配置写入被 Git 忽略的 `v2/backend/application-v2-secret.yml`，不得提交密钥。

以下为 V0.13 的本地运行方式：

应用运行时使用 MySQL 8。先创建 `wenxian` 数据库和独立应用账号，再复制 `backend/.env.example` 中的数据库变量，或在被 Git 忽略的 `backend/src/main/resources/application-secret.yml` 中配置连接。Spring Boot 启动时会执行 `schema.sql` 自动补齐表结构；不要把数据库密码提交到仓库。

数据库连接池默认最多允许 100 个连接，并保持 1 个空闲连接；可通过 `GAME_DATABASE_MAX_POOL_SIZE` 按部署环境覆盖。连接按请求逐步创建，不会在启动时一次占满 100 个连接。

先启动 Java 后端：

```bash
cd backend
mvn spring-boot:run
```

当前本地 DeepSeek 与 MySQL 配置位于被 Git 忽略的 `backend/src/main/resources/application-secret.yml`。该文件已从 Maven 资源中明确排除，不会进入 JAR；服务器应通过环境变量或外置 Spring 配置加载密钥。密钥和数据库密码都不会下发给 H5。

部署到公网域名或 IP 时，还需通过 `WENXIAN_CORS_ALLOWED_ORIGIN_PATTERNS` 配置允许访问 API 的 H5 来源，例如 `http://your-public-host:*`。该白名单用于带 Cookie 的登录请求，不应配置成任意来源 `*`。

内部试玩默认邀请码为 `WENXIAN-LOCAL`，可通过 `WENXIAN_INVITE_CODE` 环境变量替换。首次使用邮箱和邀请码登记，之后该邮箱可以直接进入；这只是早期准入机制，不等同于邮箱所有权验证。

再启动 H5 前端：

```bash
cd frontend
npm ci
npm run dev -- --host 127.0.0.1 --port 4173
```

前端通过 Vite 将 `/api` 代理到 `http://127.0.0.1:18080`。如需更换开发端口，可在启动前设置 `VITE_DEV_API_PROXY`。后端不可用时页面只展示浏览器缓存，不会在前端执行另一套规则。

## 验证

```bash
cd backend && mvn test
cd ../frontend
npm run check:runtime
npm run build
npm run test:sites
```

## 文档

- [设定与玩法](docs/game-design.md)
- [Agent 与数据架构](docs/architecture.md)
- [版本记录](docs/version-history.md)

试玩版使用 DeepSeek 作为唯一剧情 Agent；Spring Boot 负责登录、工具执行、输出校验、时间推进和权威存档。应用数据写入 MySQL；单元测试使用内存 H2，测试数据不会进入正式库。
