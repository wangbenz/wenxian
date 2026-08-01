package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.model.GameState;
import com.wenxian.game.persistence.AgentExecutionAuditStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class DeepSeekGameplayAgentService implements NarrativeAgent {
    static final int MAX_ELAPSED_MINUTES = 100 * 360 * 1440;
    private static final Logger LOGGER = LoggerFactory.getLogger(DeepSeekGameplayAgentService.class);
    private static final Set<String> PLAYER_KEYS = Set.of(
            "condition", "insight", "suspicion", "identity", "goal", "burden", "constitution"
    );
    private static final Set<String> CHARACTER_KEYS = Set.of(
            "state", "location", "relation", "identity", "realm", "condition", "combatProfile"
    );
    private static final Set<String> THREAD_KEYS = Set.of("state", "danger", "title");
    private final ObjectMapper objectMapper;
    private final WorldGenerationService deepSeek;
    private final AgentToolRegistry toolRegistry;
    private final AgentExecutionAuditStore auditStore;
    private final GameplayPromptContextAssembler contextAssembler;

    @Autowired
    public DeepSeekGameplayAgentService(ObjectMapper objectMapper, WorldGenerationService deepSeek,
                                        AgentToolRegistry toolRegistry, AgentExecutionAuditStore auditStore) {
        this.objectMapper = objectMapper;
        this.deepSeek = deepSeek;
        this.toolRegistry = toolRegistry;
        this.auditStore = auditStore;
        this.contextAssembler = new GameplayPromptContextAssembler();
    }

    DeepSeekGameplayAgentService(ObjectMapper objectMapper, WorldGenerationService deepSeek,
                                 AgentToolRegistry toolRegistry) {
        this(objectMapper, deepSeek, toolRegistry, null);
    }

    @Override
    public AgentTurn resolve(String action, GameState state, List<Map<String, Object>> history) {
        return resolve(action, state, history, deepSeek.publicConfig(), "PUBLIC");
    }

    @Override
    public AgentTurn resolve(String action, GameState state, List<Map<String, Object>> history,
                             RuntimeAiConfigService.RuntimeConfig config, String credentialSource) {
        return resolve(action, state, history, config, credentialSource,
                ActionAdjudication.accepted(
                        new ActionIntent("OTHER", null, null, null, "UNKNOWN", List.of(),
                                "MEDIUM", false, List.of()), ConflictVerdict.narrativeOnly()));
    }

    @Override
    public AgentTurn resolve(String action, GameState state, List<Map<String, Object>> history,
                             RuntimeAiConfigService.RuntimeConfig config, String credentialSource,
                             ActionAdjudication adjudication) {
        if (config == null || config.apiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "命卷推演尚未开放");
        }
        long startedAt = System.nanoTime();
        String recordedModel = config.model() + "@" + credentialSource;
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt()));
            // A retry keeps the authoritative snapshot but does not replay old prose again.
            if (attempt == 1 && history != null) messages.addAll(history);
            messages.add(Map.of("role", "user", "content", """
                    【当前权威状态】
                    %s

                    【本世难度】
                    %s

                    【Java权威行动裁决】
                    %s
                    这是不可修改的结果边界。你只负责把它写成自然剧情，不得反转胜负、伤势上限或死亡结论。

                    【本轮行动】
                    当前场景：%s
                    当前地点：%s
                    世界时间：绝天之变后第%d年，第%d日，%02d:%02d
                    玩家行动：%s
                    当前为第%d回合；第0至3回合属于命格补完期。
                    权威状态以紧凑快照提供：known开头的字段是已知实体索引，related开头的字段是本轮相关实体详情。
                    不得因索引较短而遗忘实体，也不得补写索引中不存在的既有物品、能力或人物。只有追查特殊历史细节时才调用工具。
                    完成判断后只输出约定的 JSON 裁决。
                    """.formatted(objectMapper.writeValueAsString(
                                    currentStateContext(state, action, adjudication.intent())), difficultyInstruction(state),
                    objectMapper.writeValueAsString(adjudication), state.sceneTitle, state.currentLocation,
                    state.worldYear, state.day, state.minute / 60, state.minute % 60, action, state.turn)));
            if (attempt > 1) messages.add(Map.of("role", "user", "content", """
                    上一次裁决因 JSON 结构、必填字段或状态同步不合格而被服务器拒绝。
                    请完整重写本轮裁决：严格满足 JSON 字段，CONTINUE 正文保持5至8段、约700至1100字，若承认新天赋必须同步 abilityChanges。
                    不要解释修复过程，只输出完整裁决 JSON。境界只能通过realmTransition提交，货币只能通过currencyChanges提交，二者都禁止写入playerPatch。
                    """));

            int promptTokens = 0;
            int completionTokens = 0;
            String responseId = "";
            List<Map<String, Object>> availableTools = "INVESTIGATE".equals(adjudication.intent().actionType())
                    ? toolRegistry.definitions() : List.of();
            for (int round = 0; round < 2; round++) {
                WorldGenerationService.ChatResponse response = deepSeek.requestMessage(
                        messages, round == 0 ? availableTools : List.of(), 3200, 0.72, config,
                        "GAMEPLAY", state.saveId, state.turn + 1, credentialSource);
                JsonNode message = response.message();
                promptTokens += response.promptTokens();
                completionTokens += response.completionTokens();
                responseId = response.responseId();
                JsonNode calls = message.path("tool_calls");
                if (calls.isArray() && !calls.isEmpty()) {
                    Map<String, Object> assistant = new LinkedHashMap<>();
                    assistant.put("role", "assistant");
                    assistant.put("content", message.path("content").isNull() ? "" : message.path("content").asText(""));
                    assistant.put("tool_calls", objectMapper.convertValue(calls, new TypeReference<List<Map<String, Object>>>() {}));
                    messages.add(assistant);
                    for (JsonNode call : calls) {
                        String callId = call.path("id").asText();
                        String toolName = call.path("function").path("name").asText();
                        String arguments = call.path("function").path("arguments").asText("{}");
                        messages.add(Map.of(
                                "role", "tool",
                                "tool_call_id", callId,
                                "content", toolRegistry.execute(toolName, arguments, state)
                        ));
                    }
                    continue;
                }
                String content = message.path("content").asText();
                if (content.isBlank()) throw new IllegalArgumentException("Agent 未返回裁决");
                AgentResult rawResult = objectMapper.readValue(jsonObject(content), AgentResult.class);
                if (rawResult.actionResolution() != null
                        && "ACCEPTED".equals(rawResult.actionResolution().result())
                        && LocationAuthority.requiresTransitionRepair(rawResult.locationTransition(), state,
                        rawResult.currentLocation(), adjudication.intent())) {
                    LocationRepair repair = repairLocationTransition(rawResult, state, adjudication.intent(),
                            config, credentialSource);
                    promptTokens += repair.promptTokens();
                    completionTokens += repair.completionTokens();
                    rawResult = rawResult.withLocationTransition(repair.transition());
                }
                AgentResult result = sanitize(rawResult, state, adjudication);
                audit(state, attempt, recordedModel, "SUCCESS", "", responseId, (System.nanoTime() - startedAt) / 1_000_000L);
                String canonicalContent = objectMapper.writeValueAsString(result);
                return new AgentTurn(result, canonicalContent, responseId, promptTokens, completionTokens,
                        (System.nanoTime() - startedAt) / 1_000_000L, config.model(), credentialSource);
            }
            throw new IllegalArgumentException("Agent 工具调用轮次过多");
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            lastFailure = error;
            LOGGER.warn("DeepSeek 行动裁决第 {} 次失败: {}", attempt, error.getMessage());
            audit(state, attempt, recordedModel, "FAILED", error.getClass().getSimpleName() + ": " + error.getMessage(), null,
                    (System.nanoTime() - startedAt) / 1_000_000L);
            if (error instanceof LocationAuthority.LocationValidationException
                    || error instanceof LocationRepairException) {
                break;
            }
        }
        LOGGER.error("DeepSeek 行动裁决两次均失败", lastFailure);
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "本章推演暂时中断，命卷没有发生变化，请重新推演", lastFailure);
    }

    private void audit(GameState state, int attempt, String model, String status, String detail, String responseId, long latencyMs) {
        if (auditStore == null) return;
        try {
            auditStore.record(state.saveId, state.turn + 1, model, attempt, status, detail, responseId, latencyMs);
        } catch (Exception error) {
            LOGGER.warn("Agent 审计日志写入失败: {}", error.getMessage());
        }
    }

    @Override
    public String mode() {
        return deepSeek.configured() ? "deepseek-game-agent" : "deepseek-not-configured";
    }

    private String systemPrompt() {
        return """
                你是休闲沉浸式修仙文字游戏的唯一世界裁决 Agent。必须只输出 JSON，不要 Markdown，不要解释。
                Java数据库和工具返回内容是唯一事实源。你只能推进世界，不能推翻既有历史、复活已死角色或凭空赠送高阶力量。
                主角的race、origin与originPremise属于权威创角事实，本版本不得改写；不得把灵族/妖族写成普通人族，也不得遗忘其本体根源。
                Java权威行动裁决已经确定本轮的成功边界、敌我关系、身体后果与死亡许可；不得为了戏剧性或爽感反转裁决。
                不得假设背包、功法、人物状态或旧事件；需要这些信息时先调用对应读取工具。
                NPC有独立目标；时间、伤势、钱财、追查与危险会产生合理代价。前6回合仍有新手保护，但只能影响Java裁决给出的结果范围，不能反转裁决。
                死亡完全以Java权威裁决的allowsDeath为准；高危选项必须事先明示危险。
                每轮写成一个完整章节：推进2至4个连续场景，自动处理赶路、寒暄、普通观察与低价值交流，只在会改变命运、资源、关系或风险的关键节点停下。
                玩家给出“先……再……”等复合行动时，应在合理且未被中断的前提下推进到最后一个明确步骤；sceneTitle、currentLocation与locationTransition必须对应正文最后一段的位置，严禁停留在章节开头的休息处或出发地。
                先判断玩家行动能否在当前权威状态中成立，并填写 actionResolution：
                - ACCEPTED：行动明确且可能成立，正常生成完整章节并推进状态。
                - REJECTED：行动违反境界顺序、声称拥有不存在的物品/能力、试图改写既定历史，或当前条件显然无法做到。直接说明具体原因，不生成替代剧情，不施加反噬，不推进时间和状态。
                - CLARIFY：行动对象或意图不明确，无法确定玩家究竟想做什么。直接请求补充，不推进时间和状态。
                REJECTED/CLARIFY 时只填写 actionResolution，其他剧情变化字段留空或为 null；suggestions 提供2至3条当前确实可做的小说化行动。它们是业务裁决，不是系统错误。
                CONTINUE 时 narrative 建议5至8段，总计约700至1100个简体中文字；保持小说感、具体感官与人物行动，不写流水账，不复述状态面板。不要为了凑段落拆碎句子。
                面向玩家的 narrative、signals、choices 和能力 description 必须保持小说语言，不得出现百分比、成功率、冷却时间、回合数、数值加成、系统提示或“触发判定”等 RPG 面板措辞。数值只用于 JSON 状态字段和后端裁决，不得写进正文。
                playerPatch 不得返回vitality；伤势使用condition，且必须服从Java权威裁决。characterChanges中的UPDATE只能引用knownCharacters已有id。
                threadChanges只能引用activeThreads已有id；activeThreads为空时必须返回空数组。新出现的剧情方向先写入event与signals，不得虚构剧情线id。无变化时返回空数组。
                playerPatch严禁包含silver或任何货币余额。所有收入与支出只能作为currencyChanges的增减流水提交，由Java核算最终余额；不得在inventoryChanges中新增铜钱、银两、金子或灵石等货币物品。
                凡俗钱财统一以文记账：1000文=1两银，10两银=1两金。修行货币分下品、中品、上品灵石：100下品=1中品，100中品=1上品。两套货币可以同时持有，不得无依据自动兑换。
                定价须符合世界语境且前后一致：一顿粗食约5至20文，普通客栈一夜约30至100文，凡俗短工一日约30至150文，普通药材与器物按稀缺程度合理浮动；灵石只用于修行市场。买卖物品时必须同时同步inventoryChanges，已出售物品必须REMOVE，不得仅把描述改成“已出售”。
                condition只能是STABLE、WOUNDED、SEVERE、DYING，分别表示安稳、受伤、重伤、濒死；正文只写自然伤势，不展示英文值。
                境界只能使用：凡俗、引气、筑基、金丹、元婴、化神、炼虚、合道。境界不是功法或天赋；不得创建名为“练气一层/筑基境”等境界状态的 abilityChange。
                playerPatch严禁包含realm。只有actionIntent.actionType=CULTIVATE且breakthroughAttempt=true时，才允许在realmTransition提交主角突破；普通修炼、请教功法、谈论NPC境界、未来可能性、获得感悟或稳固修为时realmTransition必须为null。
                突破成功时realmTransition.result必须为SUCCESS，fromRealm必须等于当前主角境界，toRealm只能是紧邻的下一大境界，并在正文中真实描写本次突破。修炼失败或没有突破时realmTransition必须为null。
                elapsed 为经过分钟，范围 1-51840000（最长100年），并与章节实际经过时间一致。本世一年360日，一月30日。
                玩家明确指定闭关、等待或旅行时长时，Java会在actionIntent.requestedDurationMinutes给出权威分钟数。若完整度过，elapsed和timeResolution.actualMinutes必须等于该数，completed=true；若中途被具体事件打断，actualMinutes必须小于请求值、completed=false、interruptionReason必须写明具体事件，正文也要明确表现。不得无理由缩短为一天。未指定时长时timeResolution为null。outcome 只能是 CONTINUE 或 DEAD。
                生成章节末 choices 时必须以本轮 elapsed 结束后的日期与状态为准；若已经跨日，resetRule=DAILY 的能力视为已经恢复，不得再写成“尚未恢复”“次数耗尽”或要求先浪费一轮等待。
                只有Java权威裁决allowsDeath=true时才能返回DEAD；此时playerPatch.condition必须为DYING，choices必须为空数组。
                CONTINUE 时 signals 1-3 条，choices 2-4 个；选择必须是有实质差异的关键方向。不得用梦境、回档、轮回或重生撤销死亡。
                玩家陈述“我天生拥有/从小可以”等持久设定时必须裁决，不能只在剧情中口头承认：
                - 第0至3回合允许补完最多一个低阶先天天赋；必须有限制、代价，且不得冲突既有命格。
                - 第3回合后或强度失衡时必须 REJECTED，除非本轮事件确实构成觉醒来源。
                - ACCEPTED/MODIFIED 的 ABILITY 声明必须同时在 abilityChanges 返回 LEARN；否则裁决无效。
                - REJECTED 时剧情必须明确表现为能力未成立，不能暗中沿用。
                - 剧情中任何新获得的持久天赋、功法或法术都必须同步 abilityChanges。
                玩家明确施展已有能力时，Java会扣除使用次数；不要为扣次数单独返回 abilityChanges，除非能力本身升级或永久改变。
                正文中新出现的具名人物必须在characterChanges中ADD并写明已知境界；已有人物状态变化用UPDATE。每轮最多记录4人，未确认境界必须写UNKNOWN，不能猜测。
                currentLocation是角色所在的权威城镇、山域或独立地点，默认必须原样返回。房间、摊位、院落、街巷、楼层和同一地点内的细分位置只写sceneTitle，不得改写currentLocation。
                只有角色真正离开当前权威地点时才能修改currentLocation，并且必须返回locationTransition。迁移起点必须使用当前currentLocationId；toLocationId可留空，由Java按地点名称生成稳定ID；普通步行、骑乘、乘舟等需要与elapsed相称。角色自身的传送或特殊空间能力必须引用本轮actionIntent中已验证的abilityId；外部阵门或他人强制迁移不可伪装成角色能力。
                必须输出以下 json 格式：
                {
                  "sceneId":"ascii-kebab-id","sceneTitle":"","currentLocation":"","elapsed":30,"outcome":"CONTINUE",
                  "actionResolution":{"result":"ACCEPTED|REJECTED|CLARIFY","title":"","reason":"","suggestions":["",""]},
                  "narrative":["",""],"signals":[""],
                  "choices":[{"id":"ascii-kebab-id","label":"","hint":"","tone":"default","actionType":"COMBAT|ESCAPE|INFILTRATE|NEGOTIATE|CULTIVATE|EXPLORE|INVESTIGATE|TRAVEL|USE_ITEM|USE_ABILITY|REST|OTHER","targetCharacterId":null,"targetRealm":null,"riskLevel":"LOW|MEDIUM|HIGH|LETHAL"}],
                  "playerPatch":{"condition":"STABLE","insight":7,"suspicion":30},
                  "event":{"title":"","detail":"","kind":"decision"},
                  "characterChanges":[{"operation":"ADD|UPDATE","id":"ascii-id","name":"","identity":"","realm":"凡俗|引气|筑基|金丹|元婴|化神|炼虚|合道|UNKNOWN","condition":"STABLE|WOUNDED|SEVERE|DYING","combatProfile":"","state":"","location":"","relation":""}],
                  "threadChanges":[{"id":"existing-id","values":{"state":"","danger":60}}],
                  "inventoryChanges":[{"operation":"ADD|REMOVE|UPDATE","id":"ascii-id","definitionId":"ascii-id","name":"","category":"MATERIAL","quality":"凡物","quantity":1,"description":"","keyItem":false}],
                  "currencyChanges":[{"currency":"COPPER_WEN|SPIRIT_STONE_LOW|SPIRIT_STONE_MIDDLE|SPIRIT_STONE_HIGH","amount":60,"reason":"出售药材所得"}],
                  "abilityChanges":[{"operation":"LEARN|UPDATE","id":"ascii-id","definitionId":"ascii-id","name":"","type":"TALENT|TECHNIQUE|SPELL","stage":"入门","mastery":1,"source":"","description":"","rules":"","maxUses":1,"remainingUses":1,"resetRule":"DAILY|NONE","sideEffect":""}],
                  "locationTransition":{"fromLocationId":"current-id","toLocationId":"ascii-id","toLocationName":"","scope":"LOCAL|SETTLEMENT|REGIONAL|CROSS_REGION|SPECIAL","method":"WALK|RIDE|VESSEL|ESCAPE|FORCED|TELEPORT|OTHER","abilityId":null,"reason":""},
                  "realmTransition":{"result":"SUCCESS","fromRealm":"引气","toRealm":"筑基","reason":"本轮明确完成突破的原因"},
                  "timeResolution":{"requestedMinutes":1555200,"actualMinutes":1555200,"completed":true,"interruptionReason":null},
                  "declarationResolution":{"type":"ABILITY|BACKGROUND|NONE","result":"ACCEPTED|MODIFIED|REJECTED|NOT_APPLICABLE","reason":""}
                }
                没有变化时对应数组必须为空；每轮最多3项物品、2项能力、3条线索变化、6笔货币流水。地点不变时locationTransition必须为null，没有合法突破时realmTransition必须为null。
                kind 只能是 decision、risk、gain、world；tone 只能是 default 或 danger。
                """;
    }

    private String difficultyInstruction(GameState state) {
        return "HARD".equals(state.difficulty)
                ? "困难：资源与善意更稀缺，失败后果更持久；仍须给出清晰因果与预警，不能无故处死主角。"
                : "普通：保持世界真实，但优先提供可理解的转圜、补救和成长机会，适合休闲沉浸阅读。";
    }

    AgentResult sanitize(AgentResult raw, GameState state) {
        return sanitize(raw, state, null, ConflictVerdict.narrativeOnly());
    }

    AgentResult sanitize(AgentResult raw, GameState state, ConflictVerdict verdict) {
        return sanitize(raw, state, null, verdict);
    }

    AgentResult sanitize(AgentResult raw, GameState state, ActionIntent intent) {
        return sanitize(raw, state, intent, ConflictVerdict.narrativeOnly());
    }

    private AgentResult sanitize(AgentResult raw, GameState state, ActionAdjudication adjudication) {
        return sanitize(raw, state, adjudication == null ? null : adjudication.intent(),
                adjudication == null ? ConflictVerdict.narrativeOnly() : adjudication.verdict());
    }

    private AgentResult sanitize(AgentResult raw, GameState state, ActionIntent actionIntent,
                                 ConflictVerdict verdict) {
        if (raw == null) throw new IllegalArgumentException("Agent 裁决为空");
        AgentResult.ActionResolution actionResolution = sanitizeActionResolution(raw.actionResolution());
        if (!"ACCEPTED".equals(actionResolution.result())) {
            return new AgentResult(
                    state.sceneId, state.sceneTitle, state.currentLocation, 0, "CONTINUE",
                    List.of(), List.of(), List.of(), Map.of(), null, null, null, null, null,
                    actionResolution, sanitizeDeclaration(raw.declarationResolution(), combinedAbilities(raw, state)),
                    actionIntent, verdict, List.of()
            );
        }
        String outcome = verdict != null && verdict.allowsDeath() && "DEAD".equals(verdict.verdict())
                ? "DEAD" : "CONTINUE";
        List<String> narrative = strings(raw.narrative(), "DEAD".equals(outcome) ? 2 : 2, 16);
        List<String> signals = strings(raw.signals(), 1, 3);
        List<GameState.StoryChoice> choices = sanitizeChoices(raw.choices());
        if (narrative.size() < 2 || signals.isEmpty()
                || "CONTINUE".equals(outcome) && choices.size() < 2) {
            throw new IllegalArgumentException("Agent 裁决字段不完整");
        }
        AgentResult.EventDraft event = raw.event();
        if (event == null || blank(event.title()) || blank(event.detail())) {
            throw new IllegalArgumentException("Agent 事件缺失");
        }
        String kind = Set.of("decision", "risk", "gain", "world").contains(event.kind()) ? event.kind() : "world";
        List<AgentResult.AbilityChange> abilityChanges = combinedAbilities(raw, state);
        if (raw.playerPatch() != null && raw.playerPatch().containsKey("realm")) {
            throw new RealmProgression.RealmValidationException(
                    "REALM_GENERIC_PATCH_FORBIDDEN", "境界不能通过通用角色补丁修改");
        }
        if (raw.playerPatch() != null && raw.playerPatch().containsKey("silver")) {
            throw new CurrencyAuthority.CurrencyValidationException(
                    "CURRENCY_GENERIC_PATCH_FORBIDDEN", "货币不能通过通用角色补丁修改");
        }
        Map<String, Object> playerPatch = sanitizeMap(raw.playerPatch(), PLAYER_KEYS);
        AgentResult.RealmTransition realmTransition = RealmProgression.validate(
                raw.realmTransition(), state, actionIntent);
        playerPatch = enforceCondition(playerPatch, state, verdict);
        TimeValidation time = validateTime(raw, actionIntent);
        int elapsed = time.elapsed();
        String proposedLocation = value(raw.currentLocation(), state.currentLocation);
        AgentResult.LocationTransition locationTransition = LocationAuthority.validate(
                raw.locationTransition(), state, proposedLocation, elapsed, actionIntent);
        String currentLocation = locationTransition == null
                ? state.currentLocation : locationTransition.toLocationName();
        String sceneTitle = value(raw.sceneTitle(), state.sceneTitle);
        if (locationTransition == null && !proposedLocation.equals(state.currentLocation)
                && (blank(raw.sceneTitle()) || sceneTitle.equals(state.sceneTitle))) {
            sceneTitle = proposedLocation;
        }
        List<AgentResult.InventoryChange> inventoryChanges = combinedInventory(raw, state);
        List<AgentResult.CurrencyChange> currencyChanges = CurrencyAuthority.validate(raw.currencyChanges(), state);
        return new AgentResult(
                safeId(raw.sceneId(), state.sceneId), sceneTitle,
                currentLocation, elapsed,
                outcome, narrative, signals, "DEAD".equals(outcome) ? List.of() : choices,
                playerPatch,
                new AgentResult.EventDraft(event.title().strip(), event.detail().strip(), kind),
                null, null, null, null,
                actionResolution,
                sanitizeDeclaration(raw.declarationResolution(), abilityChanges), actionIntent, verdict,
                combinedCharacters(raw, state),
                inventoryChanges, abilityChanges, combinedThreads(raw, state), locationTransition,
                realmTransition, currencyChanges, time.resolution()
        );
    }

    private TimeValidation validateTime(AgentResult raw, ActionIntent intent) {
        Integer requested = intent == null ? null : intent.requestedDurationMinutes();
        if (requested == null) {
            return new TimeValidation(clamp(raw.elapsed(), 1, MAX_ELAPSED_MINUTES), null);
        }
        int expected = clamp(requested, 1, MAX_ELAPSED_MINUTES);
        AgentResult.TimeResolution resolution = raw.timeResolution();
        if (resolution == null) {
            if (raw.elapsed() == expected) {
                return new TimeValidation(expected,
                        new AgentResult.TimeResolution(expected, expected, true, null));
            }
            throw new TimeValidationException("EXPLICIT_DURATION_UNRESOLVED",
                    "玩家指定了明确时长，但章节没有提交完成或中断凭证");
        }
        if (resolution.requestedMinutes() != expected) {
            throw new TimeValidationException("EXPLICIT_DURATION_MISMATCH",
                    "时长凭证与玩家要求不一致");
        }
        int actual = resolution.actualMinutes();
        if (resolution.completed()) {
            if (actual != expected || raw.elapsed() != expected) {
                throw new TimeValidationException("EXPLICIT_DURATION_INCOMPLETE",
                        "章节声称完成了指定时长，但实际时间没有完整推进");
            }
            return new TimeValidation(expected,
                    new AgentResult.TimeResolution(expected, expected, true, null));
        }
        if (actual < 1 || actual >= expected || raw.elapsed() != actual
                || blank(resolution.interruptionReason())) {
            throw new TimeValidationException("INVALID_TIME_INTERRUPTION",
                    "提前结束必须给出小于请求时长的实际分钟数和具体中断原因");
        }
        return new TimeValidation(actual, new AgentResult.TimeResolution(
                expected, actual, false, resolution.interruptionReason().strip()));
    }

    private record TimeValidation(int elapsed, AgentResult.TimeResolution resolution) {}

    static final class TimeValidationException extends IllegalArgumentException {
        private final String code;

        TimeValidationException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    private LocationRepair repairLocationTransition(
            AgentResult raw, GameState state, ActionIntent intent,
            RuntimeAiConfigService.RuntimeConfig config, String credentialSource) {
        try {
            String system = """
                    你是《问仙录》的地点迁移字段修复器，只补充结构化迁移凭证，不写剧情，不改变既有正文。
                    Java状态是唯一事实。只输出JSON：
                    {"locationTransition":{"fromLocationId":"","toLocationId":null,"toLocationName":"","scope":"LOCAL|SETTLEMENT|REGIONAL|CROSS_REGION|SPECIAL","method":"WALK|RIDE|VESSEL|ESCAPE|FORCED|TELEPORT|OTHER","abilityId":null,"reason":""}}
                    fromLocationId必须原样使用当前地点ID。toLocationName使用章节结束地点。
                    角色自身传送只有在usedAbilityIds中存在对应能力时才能填写TELEPORT或SPECIAL；否则使用符合行动与耗时的普通迁移方式。
                    """;
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("currentLocation", state.currentLocation);
            context.put("currentLocationId", state.currentLocationId);
            context.put("proposedLocation", raw.currentLocation());
            context.put("elapsed", clamp(raw.elapsed(), 1, MAX_ELAPSED_MINUTES));
            context.put("actionType", intent == null ? "OTHER" : intent.actionType());
            context.put("usedAbilityIds", intent == null || intent.usedAbilityIds() == null
                    ? List.of() : intent.usedAbilityIds());
            context.put("sceneTitle", raw.sceneTitle());
            WorldGenerationService.ChatResponse response = deepSeek.requestMessage(
                    List.of(Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", objectMapper.writeValueAsString(context))),
                    List.of(), 420, 0.05, config, "GAMEPLAY_STRUCTURE_REPAIR",
                    state.saveId, state.turn + 1, credentialSource);
            String content = response.message().path("content").asText();
            LocationRepairDraft draft = objectMapper.readValue(jsonObject(content), LocationRepairDraft.class);
            if (draft == null || draft.locationTransition() == null) {
                throw new LocationRepairException("地点迁移字段修复结果为空");
            }
            return new LocationRepair(draft.locationTransition(), response.promptTokens(), response.completionTokens());
        } catch (ResponseStatusException error) {
            throw error;
        } catch (LocationRepairException error) {
            throw error;
        } catch (Exception error) {
            throw new LocationRepairException("地点迁移字段修复失败", error);
        }
    }

    private String jsonObject(String content) {
        if (content == null) return "{}";
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        return start >= 0 && end > start ? content.substring(start, end + 1) : "{}";
    }

    private record LocationRepairDraft(AgentResult.LocationTransition locationTransition) {}
    private record LocationRepair(AgentResult.LocationTransition transition, int promptTokens, int completionTokens) {}

    private static final class LocationRepairException extends IllegalArgumentException {
        private LocationRepairException(String message) {
            super(message);
        }

        private LocationRepairException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    Map<String, Object> currentStateContext(GameState state) {
        return currentStateContext(state, "", null);
    }

    Map<String, Object> currentStateContext(GameState state, String action, ActionIntent intent) {
        return contextAssembler.assemble(state, action, intent);
    }

    private AgentResult.ActionResolution sanitizeActionResolution(AgentResult.ActionResolution resolution) {
        if (resolution == null) {
            return new AgentResult.ActionResolution("ACCEPTED", "", "", List.of());
        }
        String result = Set.of("ACCEPTED", "REJECTED", "CLARIFY").contains(resolution.result())
                ? resolution.result() : "ACCEPTED";
        if ("ACCEPTED".equals(result)) {
            return new AgentResult.ActionResolution(result, "", "", List.of());
        }
        String title = value(resolution.title(), "REJECTED".equals(result) ? "此念不可行" : "此念尚未成形");
        if (blank(resolution.reason())) throw new IllegalArgumentException("Agent 未说明行动裁决原因");
        List<String> suggestions = strings(resolution.suggestions(), 2, 3);
        if (suggestions.size() < 2) throw new IllegalArgumentException("Agent 未提供可行行动建议");
        return new AgentResult.ActionResolution(result, title, resolution.reason().strip(), suggestions);
    }

    private AgentResult.DeclarationResolution sanitizeDeclaration(
            AgentResult.DeclarationResolution declaration, List<AgentResult.AbilityChange> abilityChanges) {
        if (declaration == null) {
            return new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "本轮没有玩家设定声明");
        }
        String type = Set.of("ABILITY", "BACKGROUND", "NONE").contains(declaration.type())
                ? declaration.type() : "NONE";
        String result = Set.of("ACCEPTED", "MODIFIED", "REJECTED", "NOT_APPLICABLE").contains(declaration.result())
                ? declaration.result() : "NOT_APPLICABLE";
        if ("ABILITY".equals(type) && Set.of("ACCEPTED", "MODIFIED").contains(result)
                && abilityChanges.stream().noneMatch(change -> "LEARN".equals(change.operation()))) {
            throw new IllegalArgumentException("Agent承认了新天赋但没有同步能力变更");
        }
        return new AgentResult.DeclarationResolution(type, result, value(declaration.reason(), "已按世界规则裁决"));
    }

    private AgentResult.InventoryChange sanitizeInventory(AgentResult.InventoryChange change, GameState state) {
        if (change == null || !Set.of("ADD", "REMOVE", "UPDATE").contains(change.operation())) return null;
        if (CurrencyAuthority.isCurrencyInventoryChange(change)) {
            throw new CurrencyAuthority.CurrencyValidationException(
                    "CURRENCY_INVENTORY_FORBIDDEN", "货币不能作为普通物品修改");
        }
        AuthoritativeEntityResolver.Match<GameState.InventoryItem> match = AuthoritativeEntityResolver.item(
                state, change.id(), change.definitionId(), change.name(), null);
        if (match.ambiguous()) {
            throw new AuthoritativeEntityResolver.EntityValidationException(
                    "INVENTORY_CHANGE_AMBIGUOUS", "物品变化无法唯一对应当前行囊");
        }
        GameState.InventoryItem existing = match.value();
        if (("REMOVE".equals(change.operation()) || "UPDATE".equals(change.operation())) && existing == null) {
            throw new AuthoritativeEntityResolver.EntityValidationException(
                    "INVENTORY_CHANGE_TARGET_MISSING", "Agent试图修改当前行囊中不存在的物品");
        }
        if ("REMOVE".equals(change.operation()) && existing.keyItem) {
            throw new AuthoritativeEntityResolver.EntityValidationException(
                    "KEY_ITEM_REMOVE_FORBIDDEN", "关键物品不能在没有专门结算的情况下移除");
        }
        String id = existing == null ? safeId(change.id(), "item-generated") : existing.id;
        String definitionId = existing == null
                ? safeId(change.definitionId(), id) : existing.definitionId;
        String name = existing == null ? value(change.name(), "未知物品") : existing.name;
        return new AgentResult.InventoryChange(change.operation(), id,
                definitionId, name,
                value(change.category(), "MATERIAL"), value(change.quality(), "凡物"),
                clamp(change.quantity(), 1, 9999), value(change.description(), "尚未鉴定。"), change.keyItem());
    }

    private AgentResult.AbilityChange sanitizeAbility(AgentResult.AbilityChange change, GameState state) {
        if (change == null || !Set.of("LEARN", "UPDATE").contains(change.operation())) return null;
        AuthoritativeEntityResolver.Match<GameState.AbilityRecord> match = AuthoritativeEntityResolver.ability(
                state, change.id(), change.definitionId(), change.name(), null);
        if (match.ambiguous()) {
            throw new AuthoritativeEntityResolver.EntityValidationException(
                    "ABILITY_CHANGE_AMBIGUOUS", "能力变化无法唯一对应当前角色记录");
        }
        GameState.AbilityRecord existing = match.value();
        if ("UPDATE".equals(change.operation()) && existing == null) {
            throw new AuthoritativeEntityResolver.EntityValidationException(
                    "ABILITY_CHANGE_TARGET_MISSING", "Agent试图修改当前角色尚未掌握的能力");
        }
        String operation = existing != null ? "UPDATE" : change.operation();
        String id = existing == null ? safeId(change.id(), "ability-generated") : existing.id;
        String definitionId = existing == null
                ? safeId(change.definitionId(), id) : existing.definitionId;
        String name = existing == null ? value(change.name(), "未知能力") : existing.name;
        return new AgentResult.AbilityChange(operation, id,
                definitionId, name,
                Set.of("TALENT", "TECHNIQUE", "SPELL").contains(change.type()) ? change.type() : "TECHNIQUE",
                value(change.stage(), "入门"), clamp(change.mastery(), 0, 100),
                value(change.source(), "剧情领悟"), value(change.description(), "尚未完全理解。"),
                value(change.rules(), "依照能力描述生效"), clamp(change.maxUses(), 0, 99),
                clamp(change.remainingUses(), 0, Math.max(0, clamp(change.maxUses(), 0, 99))),
                Set.of("DAILY", "NONE").contains(change.resetRule()) ? change.resetRule() : "NONE",
                value(change.sideEffect(), "无"));
    }

    private List<AgentResult.InventoryChange> combinedInventory(AgentResult raw, GameState state) {
        List<AgentResult.InventoryChange> source = new ArrayList<>();
        if (raw.inventoryChanges() != null) source.addAll(raw.inventoryChanges());
        if (raw.inventoryChange() != null) source.add(raw.inventoryChange());
        List<AgentResult.InventoryChange> result = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (AgentResult.InventoryChange value : source) {
            if (result.size() == 3) break;
            AgentResult.InventoryChange sanitized = sanitizeInventory(value, state);
            if (sanitized != null && seen.add(sanitized.operation() + ":" + sanitized.id())) result.add(sanitized);
        }
        return result;
    }

    private List<AgentResult.AbilityChange> combinedAbilities(AgentResult raw, GameState state) {
        List<AgentResult.AbilityChange> source = new ArrayList<>();
        if (raw.abilityChanges() != null) source.addAll(raw.abilityChanges());
        if (raw.abilityChange() != null) source.add(raw.abilityChange());
        List<AgentResult.AbilityChange> result = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (AgentResult.AbilityChange value : source) {
            if (result.size() == 2) break;
            AgentResult.AbilityChange sanitized = sanitizeAbility(value, state);
            if (sanitized != null && seen.add(sanitized.operation() + ":" + sanitized.id())) result.add(sanitized);
        }
        return result;
    }

    private List<AgentResult.EntityPatch> combinedThreads(AgentResult raw, GameState state) {
        List<AgentResult.EntityPatch> source = new ArrayList<>();
        if (raw.threadChanges() != null) source.addAll(raw.threadChanges());
        if (raw.threadPatch() != null) source.add(raw.threadPatch());
        List<AgentResult.EntityPatch> result = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        List<String> ids = state.threads.stream().map(thread -> thread.id).toList();
        for (AgentResult.EntityPatch value : source) {
            if (result.size() == 3) break;
            AgentResult.EntityPatch sanitized = sanitizeEntity(value, ids, THREAD_KEYS);
            if (sanitized != null && seen.add(sanitized.id())) result.add(sanitized);
        }
        return result;
    }

    private List<GameState.StoryChoice> sanitizeChoices(List<GameState.StoryChoice> source) {
        if (source == null) return List.of();
        List<GameState.StoryChoice> result = new ArrayList<>();
        for (int index = 0; index < Math.min(4, source.size()); index++) {
            GameState.StoryChoice choice = source.get(index);
            if (choice == null || blank(choice.label) || blank(choice.hint)) continue;
            result.add(new GameState.StoryChoice(
                    safeId(choice.id, "choice-" + (index + 1)), choice.label.strip(), choice.hint.strip(),
                    "danger".equals(choice.tone) ? "danger" : "default",
                    normalizeActionType(choice.actionType), safeNullableId(choice.targetCharacterId),
                    normalizeRealm(choice.targetRealm), normalizeRisk(choice.riskLevel, choice.tone)
            ));
        }
        return result;
    }

    private AgentResult.EntityPatch sanitizeEntity(AgentResult.EntityPatch patch, List<String> ids, Set<String> allowedKeys) {
        if (patch == null) return null;
        if (!ids.contains(patch.id())) {
            throw new AuthoritativeEntityResolver.EntityValidationException(
                    "ENTITY_PATCH_TARGET_MISSING", "Agent试图修改当前命卷中不存在的剧情线");
        }
        Map<String, Object> values = sanitizeMap(patch.values(), allowedKeys);
        return values.isEmpty() ? null : new AgentResult.EntityPatch(patch.id(), values);
    }

    private List<AgentResult.CharacterChange> sanitizeCharacterChanges(
            List<AgentResult.CharacterChange> changes, GameState state) {
        if (changes == null) return List.of();
        List<AgentResult.CharacterChange> result = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (AgentResult.CharacterChange change : changes) {
            if (change == null || result.size() == 4) break;
            if (!Set.of("ADD", "UPDATE").contains(change.operation())) continue;
            AuthoritativeEntityResolver.Match<GameState.CharacterRecord> match =
                    AuthoritativeEntityResolver.character(state, change.id(), change.name(), null);
            if (match.ambiguous()) {
                throw new AuthoritativeEntityResolver.EntityValidationException(
                        "CHARACTER_CHANGE_AMBIGUOUS", "人物变化无法唯一对应当前角色记录");
            }
            GameState.CharacterRecord existing = match.value();
            if ("UPDATE".equals(change.operation()) && existing == null) {
                throw new AuthoritativeEntityResolver.EntityValidationException(
                        "CHARACTER_CHANGE_TARGET_MISSING", "Agent试图修改当前命卷中不存在的人物");
            }
            boolean exists = existing != null;
            String operation = exists ? "UPDATE" : "ADD";
            String id = exists ? existing.id
                    : safeId(change.id(), "character-turn-" + state.turn + "-" + (result.size() + 1));
            if (!seen.add(id)) continue;
            result.add(new AgentResult.CharacterChange(operation, id,
                    exists ? existing.name : value(change.name(), "无名者"),
                    value(change.identity(), exists ? existing.identity : "身份未知"),
                    value(normalizeRealm(change.realm()), exists ? existing.realm : "UNKNOWN"),
                    change.condition() != null && Set.of("STABLE", "WOUNDED", "SEVERE", "DYING").contains(change.condition())
                            ? change.condition() : exists ? existing.condition : "STABLE",
                    value(change.combatProfile(), exists ? existing.combatProfile : "尚未显露战斗手段"),
                    value(change.state(), exists ? existing.state : "存活"),
                    value(change.location(), exists ? existing.location : state.currentLocation),
                    value(change.relation(), exists ? existing.relation : "尚未判断")));
        }
        return result;
    }

    private List<AgentResult.CharacterChange> combinedCharacters(AgentResult raw, GameState state) {
        List<AgentResult.CharacterChange> source = new ArrayList<>();
        if (raw.characterChanges() != null) source.addAll(raw.characterChanges());
        if (raw.characterPatch() != null) {
            GameState.CharacterRecord existing = state.characters.stream()
                    .filter(value -> raw.characterPatch().id().equals(value.id)).findFirst().orElse(null);
            if (existing == null) {
                throw new AuthoritativeEntityResolver.EntityValidationException(
                        "CHARACTER_PATCH_TARGET_MISSING", "Agent试图修改当前命卷中不存在的人物");
            }
            Map<String, Object> values = sanitizeMap(raw.characterPatch().values(), CHARACTER_KEYS);
            source.add(new AgentResult.CharacterChange("UPDATE", existing.id, existing.name,
                    stringValue(values.get("identity"), existing.identity),
                    stringValue(values.get("realm"), existing.realm),
                    stringValue(values.get("condition"), existing.condition),
                    stringValue(values.get("combatProfile"), existing.combatProfile),
                    stringValue(values.get("state"), existing.state),
                    stringValue(values.get("location"), existing.location),
                    stringValue(values.get("relation"), existing.relation)));
        }
        return sanitizeCharacterChanges(source, state);
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source, Set<String> allowedKeys) {
        if (source == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (allowedKeys.contains(key) && value != null) result.put(key, value);
        });
        return result;
    }

    private Map<String, Object> enforceCondition(Map<String, Object> patch, GameState state, ConflictVerdict verdict) {
        Map<String, Object> result = new LinkedHashMap<>(patch);
        String current = state.player == null || state.player.condition == null ? "STABLE" : state.player.condition;
        String proposed = result.get("condition") == null ? current : String.valueOf(result.get("condition"));
        List<String> conditions = List.of("STABLE", "WOUNDED", "SEVERE", "DYING");
        if (!conditions.contains(current)) current = "STABLE";
        if (!conditions.contains(proposed)) proposed = current;
        int distance = conditions.indexOf(proposed) - conditions.indexOf(current);
        if (distance > 1) proposed = conditions.get(conditions.indexOf(current) + 1);
        if (distance < -1) proposed = conditions.get(conditions.indexOf(current) - 1);
        if (verdict != null && verdict.playerConditionAfter() != null) proposed = verdict.playerConditionAfter();
        result.put("condition", proposed);
        return result;
    }

    private String normalizeActionType(String value) {
        if (value == null) return "OTHER";
        return Set.of("COMBAT", "ESCAPE", "INFILTRATE", "NEGOTIATE", "CULTIVATE", "EXPLORE",
                        "INVESTIGATE", "TRAVEL", "USE_ITEM", "USE_ABILITY", "REST", "OTHER")
                .contains(value) ? value : "OTHER";
    }

    private String normalizeRisk(String value, String tone) {
        if (value == null) return "danger".equals(tone) ? "HIGH" : "MEDIUM";
        return Set.of("LOW", "MEDIUM", "HIGH", "LETHAL").contains(value)
                ? value : "danger".equals(tone) ? "HIGH" : "MEDIUM";
    }

    private String normalizeRealm(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.contains("练气") || value.contains("炼气")) return "引气";
        return List.of("凡俗", "引气", "筑基", "金丹", "元婴", "化神", "炼虚", "合道")
                .stream().filter(value::contains).findFirst().orElse(null);
    }

    private String safeNullableId(String value) {
        return value == null || value.isBlank() ? null : safeId(value, null);
    }

    private List<String> strings(List<String> source, int minimum, int maximum) {
        if (source == null) return List.of();
        List<String> result = source.stream().filter(value -> value != null && !value.isBlank())
                .map(String::strip).limit(maximum).toList();
        return result.size() < minimum ? List.of() : result;
    }

    private static String safeId(String value, String fallback) {
        String result = value == null ? "" : value.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        return result.isBlank() ? fallback : result.substring(0, Math.min(64, result.length()));
    }

    private static String value(String candidate, String fallback) {
        return blank(candidate) ? fallback : candidate.strip();
    }

    private static String stringValue(Object candidate, String fallback) {
        return candidate == null || String.valueOf(candidate).isBlank()
                ? fallback : String.valueOf(candidate).strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
