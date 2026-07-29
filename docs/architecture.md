# Agent 与数据架构

## 职责边界

```text
React H5
  └─ 展示页面、提交选择或自由行动

Spring Boot
  ├─ 邮箱/邀请码会话
  ├─ 固定世界与姓名目录
  ├─ 存档归属和统一时间
  ├─ 输入、境界、物品与补丁校验
  ├─ MySQL 8 权威数据库
  └─ NarrativeAgent 接口
       └─ DeepSeekGameplayAgentService
            ├─ Prompt
            ├─ AgentToolRegistry
            └─ DeepSeek API
```

Agent 不切换页面、不执行 SQL、不修改固定世界，也不能绕过 Java 直接提高境界或生成物品。它负责理解行动、读取必要状态、演绎 NPC、生成叙事、提出时间和结构化变化。

## 数据持久化

当前应用数据库为 MySQL 8，单元测试使用隔离的内存 H2。已有表：

- `app_user`：试玩用户；
- `invite_code`：邀请码、次数和状态；
- `user_session`：HttpOnly Cookie 会话摘要；
- `game_save`：用户唯一存档及结构化 JSON 快照；
- `game_turn_request`：行动请求幂等记录。
- `game_life_archive`：死亡后转世重开时封存的前世快照。
- `game_life_turn_archive`：前世逐回合文本、状态差量、Token 与耗时；新发生的重开和死亡均会完整归档。
- `agent_conversation`：一张命卷对应一个持续会话及滚动摘要；
- `agent_message`：按顺序保存玩家与 Agent 的完整消息；
- `game_turn`：保存行动、章节、选项、状态差量、Token 与延迟指标。
- `agent_execution`：独立记录每次 DeepSeek 推演尝试的成败、模型、响应 ID、延迟与经脱敏的失败原因，主事务回滚时仍保留。
- `release_note` / `user_release_receipt`：版本公告、发布状态、全员展示开关、登录页置顶标记和逐用户已读状态；登录页只读取运营明确置顶的一条公开公告。
- `ai_model_config`：V4 Pro/Flash 当前启用状态与加密密钥。密钥只在服务器端解密，不通过管理台接口回传明文。

测试服务器使用独立的 `wenxian` 数据库与最小权限应用账号。真实连接只保存在后端私有配置或部署环境变量中；生产/试玩运行不依赖本地 H2 文件，因此服务重启后数据仍由 MySQL 持久保存。

快照保存当前权威状态，会话表负责叙事连续性，回合表负责审计与问题复现。短命卷会发送完整历史；历史超过约 3.6 万字符后，Java 使用权威角色状态、关键关系与最近事件生成滚动摘要，并只附带不超过 16 条、约 2.2 万字符的近期完整消息。数据库仍永久保留原始消息，需要追查旧事时由 Agent 通过只读工具获取。DeepSeek 响应 ID 只用于诊断，不承担会话恢复。

## 自定义 Agent Runtime

第一阶段不引入 LangChain，核心接口为：

```java
public interface NarrativeAgent {
    AgentTurn resolve(String action, GameState state, List&lt;Map&lt;String, Object&gt;&gt; history);
    String mode();
}
```

DeepSeek V4 Pro 调用采用 OpenAI 兼容 Tool Calls。Java 会主动附带当前权威状态，常规回合通常一次模型请求完成；只有追查细节时才使用只读工具：

- `get_player_state`
- `get_inventory`
- `get_abilities`
- `get_character`
- `get_recent_events`
- `get_active_threads`

工具执行时由 Java 隐式绑定当前用户和存档，Agent 无法读取其他玩家或尚未被玩家认识的人物。最终裁决以 JSON 返回，经过白名单和数值范围校验后才写入数据库。`actionResolution.result` 只能为 `ACCEPTED`、`REJECTED` 或 `CLARIFY`；后两者不进入 `GameEngine`，因此无法修改存档或世界时间。

后续接入 LangChain4j、Spring AI 或独立 LangChain 服务时，只替换 `NarrativeAgent` 实现；工具 JSON Schema、游戏接口、数据库校验和事务边界继续保留。

模型配置由管理台写入共享数据库。V4 Pro 与 V4 Flash 使用同一套 Agent、工具和 Java 校验，只改变本轮请求的模型名称；切换对下一次请求立即生效，不中断已经发出的请求。单独维护的 API Key 使用 AES-GCM 加密，主密钥由 `WENXIAN_AI_CONFIG_MASTER_KEY` 注入；未单独设置时沿用游戏后端的私有环境配置。

## 单轮流程

```text
玩家行动
→ 基础输入门控
→ 检查存档归属、版本和 requestId
→ Java 硬规则裁决（无意义输入、明确跨境突破等）
   ├─ REJECTED / CLARIFY：保存会话记录，原样返回原因与可行建议，世界状态不变
   └─ ACCEPTED：继续加载命卷
→ 加载同一命卷历史与当前权威状态
→ 结束读取事务并释放数据库连接
→ DeepSeek V4 Pro 先裁决行动语义
   ├─ REJECTED / CLARIFY：返回原因与建议，不生成替代章节，世界状态不变
   └─ ACCEPTED：生成完整小章节，按需调用只读工具
→ JSON、关键状态同步或正文总长度失败时自动携带修复约束重试一次；不再因段落数形式差异重写整章
→ 返回叙事和结构化补丁
→ Java 校验角色 ID、境界跨度、数值、物品和天赋声明一致性
→ 推进统一时间
→ 开启短事务，复核存档版本并原子写入事件、存档、会话消息和回合审计
→ 返回新场景、选项及页面失效数据
```

同一存档一次只处理一轮行动；`stateVersion` 防止旧页面覆盖新状态，`requestId` 防止网络重试造成重复生成。模型等待阶段不占用数据库事务或连接，适合多名试玩用户同时推进各自命卷。

明确境界变更还有第二道 Java 强校验：只能从当前境界进入紧邻的下一境界，并且正文或结构化变化中必须存在明确突破事实。即使 Agent 错误返回“凡俗直接元婴”，补丁也会被拒绝，不依赖提示词维持境界规则。

`POST /turns` 的成功响应可选携带：

```json
{
  "actionResolution": {
    "result": "REJECTED",
    "title": "此念不可行",
    "reason": "你目前仍在凡俗之境……",
    "suggestions": ["寻找引气法门", "打听灵根测试", "先处理眼前局势"]
  }
}
```

此时 HTTP 请求本身成功，`revision`、`state.turn` 和世界时间保持原值。只有网络、模型超时、响应损坏等技术故障才返回错误状态。

开局前三回合允许补完一项低阶先天天赋。Agent 若接受或修改玩家声明，必须同时提交 `abilityChange`；Java 拒绝“叙事承认但能力未落库”的裁决。受限能力记录次数、恢复规则、边界和代价，使用次数由 Java 扣减。上述机械字段只用于服务端裁决，不直接展示；H5 仅呈现小说化能力描述、来历和当前领悟阶段。

## 已实现 API

```text
POST   /api/v1/auth/login
GET    /api/v1/auth/me
POST   /api/v1/auth/logout

GET    /api/v1/game/bootstrap
GET    /api/v1/release-notes
GET    /api/v1/release-notes/pending
POST   /api/v1/release-notes/{releaseId}/read
POST   /api/v1/character-names/generate
POST   /api/v1/character-names/validate

POST   /api/v1/saves
GET    /api/v1/saves/current
GET    /api/v1/saves/{saveId}
DELETE /api/v1/saves/{saveId}
POST   /api/v1/saves/{saveId}/protagonist
POST   /api/v1/saves/{saveId}/talents/reroll
POST   /api/v1/saves/{saveId}/talents/select
POST   /api/v1/saves/{saveId}/talents/skip
POST   /api/v1/saves/{saveId}/turns

GET    /api/v1/saves/{saveId}/scene
GET    /api/v1/saves/{saveId}/state
GET    /api/v1/saves/{saveId}/character-sheet
GET    /api/v1/saves/{saveId}/inventory
GET    /api/v1/saves/{saveId}/abilities
GET    /api/v1/saves/{saveId}/characters
GET    /api/v1/saves/{saveId}/timeline
GET    /api/v1/saves/{saveId}/threads
GET    /api/v1/saves/{saveId}/world
```

管理台接口另包含玩家多条件筛选、账号下当前/历史角色、逐角色回合分页与跳转、版本公告维护、模型切换和操作日志。管理台可以显示完整邮箱以协助小范围测试用户找回账号，但这些接口仍受独立管理员会话保护。

DeepSeek API Key 只存在于后端配置。仓库中的 `application-secret.yml` 被 Git 忽略，H5 永远不会接收或保存该密钥。
