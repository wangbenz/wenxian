# 问仙录 H5 试玩

响应式对话式修仙文字游戏 H5。页面直接占满浏览器视口，不使用手机设备套壳；快捷行动与自由输入都会推进统一世界时间，并同步角色、纪事和线索状态。

## 项目结构

```text
wenxian/
├── frontend/   React + TypeScript + Vite 响应式 H5
├── backend/    Java 21 + Spring Boot 权威游戏服务
└── docs/       玩法与 Agent/数据架构
```

## 本地运行

先启动 Java 后端：

```bash
cd backend
mvn spring-boot:run
```

再启动 H5 前端：

```bash
cd frontend
npm ci
npm run dev -- --host 127.0.0.1 --port 4173
```

前端通过 Vite 将 `/api` 代理到 `http://127.0.0.1:8080`。后端不可用时页面只展示浏览器缓存，不会在前端执行另一套规则。

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

试玩版使用 Java 规则 Agent：行动、时间推进、人物变化和存档均由 Spring Boot 后端裁决，数据写入 `backend/.data/game-saves.json`。后续可将规则 Agent 替换为模型 Agent，并将 JSON 存档替换为 PostgreSQL。
