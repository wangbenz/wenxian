# Agent 与数据架构

## 目标

数据库维护真实世界，Agent 负责理解行动与演绎结果。叙事文本不是事实源，所有可持续变化必须来自结构化事件。

试玩版本已拆成独立 React H5 与 Spring Boot 服务。前端只提交 `saveId + actionId/freeText`；Java 后端从磁盘读取权威状态、运行规则 Agent、推进统一时间，再把完整快照原子写入 `backend/.data/game-saves.json`。浏览器中的 `localStorage` 只是只读缓存，后端断开时不能继续裁决。

```text
React H5 :4173
      │ /api（Vite 开发代理）
Spring Boot :8080
      ├─ GameController：API 与输入校验
      ├─ GameService：单轮事务边界
      ├─ RuleAgentService：行动意图与剧情裁决
      ├─ GameEngine：时间、事件、角色与任务补丁
      └─ SaveStore：JSON 原子持久化
```

## 单轮 Agent 合约

输入至少包含：统一世界时间、当前场景、玩家状态、相关角色的玩家已知状态、相关历史事件、未解线索、玩家行动。

输出必须同时包含：

```json
{
  "sceneId": "bargain",
  "elapsed": 24,
  "narrative": ["剧情正文"],
  "signals": ["局势变化"],
  "choices": [{ "id": "accept-job", "label": "接下差事" }],
  "playerPatch": { "suspicion": 42 },
  "event": { "title": "以命赊药", "kind": "decision" },
  "characterPatch": { "id": "xu-baichuan", "state": "等待答复" },
  "threadPatch": { "id": "medicine", "state": "得到一片药叶" }
}
```

后端应先校验时间、角色 ID、数值边界和历史冲突，再以事务写入；校验失败时让 Agent 修正，不直接把矛盾写进存档。

## 建议的数据表

第一版保持精简：

- `game_save`：存档与当前世界时间。
- `character`：每个正式登场角色一条主档案。
- `character_state_history`：境界、位置、伤势、身份等历史变化。
- `world_event`：所有真实变化的事件源。
- `event_character`：事件参与者与角色变化。
- `player_knowledge`：玩家已经知道的事实，与真实状态隔离。
- `quest_thread`：承诺、危机、任务和未解线索。
- `relationship`：人物关系、信任与最后互动时间。

## 统一时间线

Agent 只能返回 `elapsed`，不能直接决定最终日期。服务端负责：

1. 基于当前时间累加经过时长；
2. 处理跨日、寿元与长期行动；
3. 推进离场 NPC 的计划；
4. 生成角色状态历史；
5. 向叙事 Agent 提供「当前有效快照」。

再次提到某角色时，先查询其主档案、上次确认时间、期间事件与玩家知识，再生成此刻的可见状态，避免人物因上下文切换而停滞或重置。

## Agent 分层

- 意图层：把自由文本转换为行动目标、对象、方式和约束。
- 世界裁决层：读取数据、计算时间与结果，产出结构化补丁。
- 叙事层：只依据裁决结果写剧情，不自行修改事实。
- 校验层：检查境界、时间、死亡状态、物品归属与承诺冲突。

生产版本可把当前规则 Agent 替换为模型 Agent；规则引擎仍保留，负责数值边界、时间计算和数据库事务。

## 已实现 API

- `GET /api/health`：Agent 与存储状态。
- `GET /api/game?saveId=...`：读取或创建权威存档。
- `POST /api/game/action`：提交行动，由服务端裁决并持久化。
- `POST /api/game/reset`：重置当前试玩存档。

当前 JSON 文件同时保存事件与最新快照，适合单机试玩。多人或线上环境下一步应替换为 PostgreSQL，并增加事务级行动幂等、角色历史表和玩家知识表。模型 Agent 只输出结构化补丁，仍不得直接操作数据库。
