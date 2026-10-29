# 玩家实际意图的通用模拟案例

`player-intent/` 为手工泛化的完整 v2 测试前态与行动，源于只读抽样的实际提交记录。它们不是数据库导出、精确旧存档复原或默认剧情。`sourceEvidence` 标明前态重构和跨世界映射边界，不进入模型上下文；真实来源映射保留在仓库外私有目录。

`player-intent-rubrics.json` 是按 C01–C12 编号的人工审读标准，不能拼入模型 Prompt。真实生成允许不同合理结果，不要求每案晋升、保证逃脱或强制 NPC 留在原处。

宿主位于 `src/test/java/com/wenxian/game/story/replay/playerintent`，复用正式类型化模型与准备、提交服务，使用独立 H2，绕过账号 API/额度门禁。不会加载 Spring 应用的数据源，也不替代 MySQL、H5、权限、扣费或连续多轮验收。

## 离线验证

在 `apps/game-api` 使用 Java 21：

```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  /opt/homebrew/bin/mvn -o -Dtest=PlayerIntentReplayTest test
```

对外部案例做无模型 preflight，先复制到仓库外权限 700 的临时目录，不能将输出写回资源目录：

```sh
PLAYER_CASE_ROOT=$(mktemp -d)
mkdir -m 700 "$PLAYER_CASE_ROOT/cases" "$PLAYER_CASE_ROOT/results"
cp src/test/resources/story/replay/player-intent/*.json "$PLAYER_CASE_ROOT/cases/"
env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  PLAYER_INTENT_PREFLIGHT=1 \
  PLAYER_INTENT_CASE_DIRECTORY="$PLAYER_CASE_ROOT/cases" \
  PLAYER_INTENT_OUTPUT_DIR="$PLAYER_CASE_ROOT/results" \
  /opt/homebrew/bin/mvn -o -Dtest=PlayerIntentReplayPreflightTest test
```

`VALID_UNTIL_DIRECTOR` 表示能到达导演边界，不意味着资料完整或玩法合格。官方当前案例还应逐案检查 `directorContext.worldContext.materials` 存在与当前境界匹配的 `PROGRESSION_STAGE`，且没有修行体系未绑定标记。v2 当前地域/标签映射的已知限制单独保留。

## 受控真实模型

须已获得用户授权，先审读案例、冻结代码/Prompt/包/输入哈希并声明整批调用上限。使用仓库脚本 `scripts/capture-synthetic-model.py` 的回环代理；真实入口不自动起代理，不自动申请新预算。模型凭证经私有环境注入，不记录到文件、终端或报告。

显式环境包括 `PLAYER_INTENT_REPLAY=1`、`PLAYER_INTENT_CASE_DIRECTORY`（或单案 `PLAYER_INTENT_CASE_FILE`，二选一）、`PLAYER_INTENT_OUTPUT_DIR`、`PLAYER_INTENT_PROXY_DIR`、`PLAYER_INTENT_BASE_URL`、`DEEPSEEK_API_KEY`，然后只运行 `PlayerIntentReplayRealModelTest`。案例、输出、代理目录均需仓库外权限 700。代理只允许 `http://127.0.0.1:<高位端口>/v1`，上游为固定 DeepSeek。

真实入口先检查整批前态，再顺序执行。每案有独立 started 门闩，失败记录原件并继续其他案；结果写盘失败则停止。失败过的同一案例不能直接删除门闩重发，需先审查原件；修改输入的独立实验应另存案例/输出与清单，同时保留已消耗的总调用预算。

技术测试中有故意错分组的最小反例，它用来记录现有恢复边界，不能当成真实玩家行为或已修复的协议。
