# Agent 与数据架构

## 职责边界

```text
React H5
  └─ 展示页面、提交选择或自由行动

Spring Boot
  ├─ 邮箱/密码会话
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

## 公网入口与 HTTPS

```text
手机浏览器
  └─ HTTPS: wenxianlu.cn / www.wenxianlu.cn
       └─ 阿里云香港轻量服务器 8.217.11.8
            ├─ Nginx 提供 H5 静态文件
            └─ /api/ 反向代理至 116.198.232.119:25173
                 └─ 京东云 Nginx → Spring Boot H5 后端
                      └─ 阿里云 RDS MySQL 8（上海）

管理台浏览器
  └─ 京东云管理台入口 → Spring Boot 管理台后端 → 同一 RDS
```

公网 H5 域名的阿里云 DNS `@` 与 `www` A 记录均指向香港服务器 `8.217.11.8`。香港实例为 `c895cacc73c9474a83623882f93853e1`（`Ubuntu-dhmd`，中国香港，Ubuntu 22.04），只承担 Nginx、H5 静态资源、TLS 终止和 API 转发；Java、管理台及数据库继续运行在原有环境，不能因增加香港入口而重复部署一套权威后端。

香港静态目录为 `/var/www/wenxian/current`，Nginx 配置为 `/etc/nginx/sites-available/wenxian`。`/api/` 的稳定上游是京东云公开 H5 入口 `http://116.198.232.119:25173`，由京东云 Nginx 再转发至正式 Java 端口。若静态首页正常但接口为 `502`，应依次检查京东云 `25173`、正式 Java 服务及其健康接口，不应重签证书或重建香港服务器。

Let's Encrypt 证书覆盖 `wenxianlu.cn` 与 `www.wenxianlu.cn`，当前证书目录为 `/etc/letsencrypt/live/wenxianlu.cn/`，首次签发有效期至 2026-10-27，之后由已启用的 `certbot.timer` 自动续期。实际有效期以 `certbot certificates` 为准。HTTP 80 仅用于 ACME 校验并重定向至 HTTPS 443；不得提交证书私钥、SSH 私钥或服务器密码。

当前 Mac 的部署身份为 `/Users/ben/.ssh/id_ed25519_wenxian_hk`，连接目标 `root@8.217.11.8`。发布 H5 时应使用原子目录或版本软链接，至少验证：HTTP 跳转 HTTPS、两个 HTTPS 域名返回 200、一个带哈希静态资源返回 200，以及京东云后端启动后的 `/api/v1/health`。DNS 切换会改变全部玩家流量，必须取得用户明确授权。

## 数据持久化

当前应用数据库为 MySQL 8，单元测试使用隔离的内存 H2。已有表：

- `app_user`：试玩用户；
- `invite_code`：旧内测邀请码归档；新注册流程不再使用。
- `auth_registration` / `auth_login_failure`：注册网络限制与登录失败窗口记录；只存不可逆摘要。
- `user_session`：HttpOnly Cookie 会话摘要；
- `game_save`：用户唯一存档及结构化 JSON 快照；
- `game_turn_request`：行动请求幂等记录。
- `game_life_archive`：死亡或主动重开时封存的前世快照，并以 `end_reason` 区分 `DEAD`、`RESTARTED` 与无法追溯原因的旧记录 `LEGACY`。
- `game_life_turn_archive`：前世逐回合文本、状态差量、Token 与耗时；新发生的重开和死亡均会完整归档。
- `agent_conversation`：一张命卷对应一个持续会话及滚动摘要；
- `agent_message`：按顺序保存玩家与 Agent 的完整消息；
- `game_turn`：保存行动、章节、选项、状态差量、Token 与延迟指标。
- `ai_usage_log`：按每一次模型 HTTP 请求记录操作类型、平台或玩家密钥来源、模型、输入/输出及缓存 Token、结果和耗时。管理台成本观察以此表为准；`game_turn` 的 Token 仅用于解释单个成功章节，不再作为平台总消耗口径。
- `agent_execution`：独立记录每次 DeepSeek 推演尝试的成败、模型、响应 ID、延迟与经脱敏的失败原因，主事务回滚时仍保留。
- `release_note` / `user_release_receipt`：版本公告、发布状态、全员展示开关、登录页置顶标记和逐用户已读状态；登录页只读取运营明确置顶的一条公开公告。
- `ai_model_config`：V4 Pro/Flash 当前启用状态与加密密钥。密钥只在服务器端解密，不通过管理台接口回传明文。
- `gameplay_config`：账号赠送回合总量及玩家自备密钥开关，默认每个账号 20 回。
- `user_daily_quota`：按北京时间分日记录平台回合消耗，便于运营统计；账号剩余额度按所有日期累计值计算，不会每日重置。
- `user_ai_credential`：玩家自备 DeepSeek 密钥的 AES-256-GCM 密文、尾号与验证状态；账号注销时级联删除。

测试服务器的权威数据已于 2026-07-29 迁移至阿里云 RDS MySQL 8，H5 后端和管理台共用 `wenxian` 数据库与最小权限应用账号。真实连接只保存在服务器外置 Spring 配置或部署环境变量中；仓库不记录 RDS 密码、API Key、数据库转储或测试用户数据。生产/试玩运行不依赖本地 H2 文件，因此服务重启后数据仍由 RDS 持久保存。

账号认证使用 BCrypt 密码摘要。新账号注册时自行设置密码；迁移前 `password_hash` 为空的老账号可用其规范化后的完整邮箱地址完成一次初始化登录，H5 随即展示不可跳过的改密界面，保存新密码后该初始化口令立即失效。

迁移时先停止两套后端并冻结写入，随后导出业务库、保留异机备份，再导入空的 RDS 数据库。切换前后均为 21 张业务表，所有表的精确行数逐表一致。京东云测试服务器上的旧 Docker MySQL 仅作为短期回滚源保留，正常运行时必须保持停止，也不得继续对公网开放 3306 端口。后续启用 SSL 时，应先更新 RDS 证书与 JDBC 配置，再分别验证 H5、管理台和数据库读取接口。

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
→ 选择玩家自备密钥或公共密钥；公共密钥先检查账号赠送回合余额
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

只有实际得到模型裁决结果的公共密钥调用才计入额度；Java 本地拦截、网络故障和模型服务故障不计入。Agent 返回的业务拒绝或补充询问已经发生模型成本，因此计入一次。自备密钥回合不受公共额度限制，但仍受存档单请求锁与所有 Java 规则约束。密钥保存前会以极小请求验证，接口只返回是否启用、尾号和验证时间，不返回明文；正式开放该入口前必须使用 HTTPS，并通过外部环境变量设置独立主密钥。

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
POST   /api/v1/auth/register
GET    /api/v1/auth/me
POST   /api/v1/auth/logout
POST   /api/v1/auth/password
DELETE /api/v1/auth/account

GET    /api/v1/ai/settings
PUT    /api/v1/ai/credential
DELETE /api/v1/ai/credential

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

管理台接口另包含玩家多条件筛选、当前角色回合榜、账号下当前/历史角色、逐角色回合分页与跳转、账号去重的激活漏斗、近 7/14 日注册与 Token 趋势、版本公告维护、模型切换、账号赠送回合、自备密钥开关、临时密码、登录解锁和操作日志。激活漏斗的“三回合/五回合”必须由同一个角色达成，不能把多个前世的回合数相加；回合榜也只比较当前命卷，不叠加历史角色。管理台可以显示完整邮箱以协助小范围测试用户找回账号，但这些接口仍受独立管理员会话保护。

新账号注册数由 `gameplay_config.registration_max_users` 控制，默认 1000。注册事务会锁定该配置并核对 `app_user` 总数；达到上限时只拒绝 `/auth/register`，返回运营配置的满员提示和 QQ 联系说明。已有账号的 `/auth/login` 不读取该限制，因此不会受到影响。管理台“运营配置”页面可以实时修改上限和两段提示文案。

模型调用记录按“供应商、模型、密钥来源”三个维度保存。当前供应商是 DeepSeek；后续新增其他供应商时，通过统一模型适配器写入相同的 `ai_usage_log`，管理台的请求量、Token、失败率和耗时统计无需改表重做。

平台模型 API Key 只存在于后端配置。玩家自备密钥仅由 H5 输入一次并通过 HTTPS 送到后端加密保存，浏览器不写入本地缓存；仓库中的 `application-secret.yml` 被 Git 忽略。
