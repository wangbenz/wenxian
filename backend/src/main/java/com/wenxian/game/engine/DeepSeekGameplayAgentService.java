package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(DeepSeekGameplayAgentService.class);
    private static final Set<String> PLAYER_KEYS = Set.of(
            "vitality", "silver", "insight", "suspicion", "identity", "goal", "burden", "realm", "constitution"
    );
    private static final Set<String> CHARACTER_KEYS = Set.of("state", "location", "relation", "identity");
    private static final Set<String> THREAD_KEYS = Set.of("state", "danger", "title");
    private final ObjectMapper objectMapper;
    private final WorldGenerationService deepSeek;
    private final AgentToolRegistry toolRegistry;
    private final AgentExecutionAuditStore auditStore;

    @Autowired
    public DeepSeekGameplayAgentService(ObjectMapper objectMapper, WorldGenerationService deepSeek,
                                        AgentToolRegistry toolRegistry, AgentExecutionAuditStore auditStore) {
        this.objectMapper = objectMapper;
        this.deepSeek = deepSeek;
        this.toolRegistry = toolRegistry;
        this.auditStore = auditStore;
    }

    DeepSeekGameplayAgentService(ObjectMapper objectMapper, WorldGenerationService deepSeek,
                                 AgentToolRegistry toolRegistry) {
        this(objectMapper, deepSeek, toolRegistry, null);
    }

    @Override
    public AgentTurn resolve(String action, GameState state, List<Map<String, Object>> history) {
        if (!deepSeek.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "命卷推演尚未开放");
        }
        long startedAt = System.nanoTime();
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt()));
            if (history != null) messages.addAll(history);
            messages.add(Map.of("role", "user", "content", """
                    【当前权威状态】
                    %s

                    【本世难度】
                    %s

                    【本轮行动】
                    当前场景：%s
                    当前地点：%s
                    世界时间：绝天之变后第%d年，第%d日，%02d:%02d
                    玩家行动：%s
                    当前为第%d回合；第0至3回合属于命格补完期。
                    权威状态已经完整提供。只有追查特殊细节时才调用工具，避免重复读取。完成判断后只输出约定的 JSON 裁决。
                    """.formatted(objectMapper.writeValueAsString(currentStateContext(state)), difficultyInstruction(state), state.sceneTitle, state.currentLocation,
                    state.worldYear, state.day, state.minute / 60, state.minute % 60, action, state.turn)));
            if (attempt > 1) messages.add(Map.of("role", "user", "content", """
                    上一次裁决因 JSON 结构、必填字段或状态同步不合格而被服务器拒绝。
                    请完整重写本轮裁决：严格满足 JSON 字段，CONTINUE 正文保持6至10段、800至1200字，若承认新天赋必须同步 abilityChange。
                    不要解释修复过程，只输出完整裁决 JSON。
                    """));

            int promptTokens = 0;
            int completionTokens = 0;
            String responseId = "";
            for (int round = 0; round < 2; round++) {
                WorldGenerationService.ChatResponse response = deepSeek.requestMessage(
                        messages, toolRegistry.definitions(), 3200, 0.72);
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
                AgentResult result = sanitize(objectMapper.readValue(content, AgentResult.class), state);
                int narrativeLength = result.narrative().stream().mapToInt(String::length).sum();
                if ("ACCEPTED".equals(result.actionResolution().result())
                        && "CONTINUE".equals(result.outcome())
                        && narrativeLength < 700) {
                    throw new IllegalArgumentException("Agent 章节过短：" + result.narrative().size() + "段/" + narrativeLength + "字");
                }
                audit(state, attempt, "SUCCESS", "", responseId, (System.nanoTime() - startedAt) / 1_000_000L);
                return new AgentTurn(result, content, responseId, promptTokens, completionTokens,
                        (System.nanoTime() - startedAt) / 1_000_000L);
            }
            throw new IllegalArgumentException("Agent 工具调用轮次过多");
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            lastFailure = error;
            LOGGER.warn("DeepSeek 行动裁决第 {} 次失败: {}", attempt, error.getMessage());
            audit(state, attempt, "FAILED", error.getClass().getSimpleName() + ": " + error.getMessage(), null,
                    (System.nanoTime() - startedAt) / 1_000_000L);
        }
        LOGGER.error("DeepSeek 行动裁决两次均失败", lastFailure);
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "本章推演暂时中断，命卷没有发生变化，请重新推演", lastFailure);
    }

    private void audit(GameState state, int attempt, String status, String detail, String responseId, long latencyMs) {
        if (auditStore == null) return;
        try {
            auditStore.record(state.saveId, state.turn + 1, deepSeek.model(), attempt, status, detail, responseId, latencyMs);
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
                不得假设背包、功法、人物状态或旧事件；需要这些信息时先调用对应读取工具。
                NPC 有独立目标；时间、伤势、银钱、追查与危险会产生合理代价，但前6回合属于新手保护期，失败优先转化为受伤、失败、新债务或错失机缘。
                只有玩家明知高危仍选择自杀式行动，或生命值已归零且无合理脱身手段时，才能判定死亡。高危选项必须事先明示危险。
                每轮写成一个完整章节：推进2至4个连续场景，自动处理赶路、寒暄、普通观察与低价值交流，只在会改变命运、资源、关系或风险的关键节点停下。
                先判断玩家行动能否在当前权威状态中成立，并填写 actionResolution：
                - ACCEPTED：行动明确且可能成立，正常生成完整章节并推进状态。
                - REJECTED：行动违反境界顺序、声称拥有不存在的物品/能力、试图改写既定历史，或当前条件显然无法做到。直接说明具体原因，不生成替代剧情，不施加反噬，不推进时间和状态。
                - CLARIFY：行动对象或意图不明确，无法确定玩家究竟想做什么。直接请求补充，不推进时间和状态。
                REJECTED/CLARIFY 时只填写 actionResolution，其他剧情变化字段留空或为 null；suggestions 提供2至3条当前确实可做的小说化行动。它们是业务裁决，不是系统错误。
                CONTINUE 时 narrative 建议6至10段，总计约800至1200个简体中文字，最低不得少于700字；保持小说感、具体感官与人物行动，不写流水账，不复述状态面板。不要为了凑段落拆碎句子。
                面向玩家的 narrative、signals、choices 和能力 description 必须保持小说语言，不得出现百分比、成功率、冷却时间、回合数、数值加成、系统提示或“触发判定”等 RPG 面板措辞。数值只用于 JSON 状态字段和后端裁决，不得写进正文。
                playerPatch 使用更新后的绝对数值，不是增量。characterPatch/threadPatch 只能引用输入中已有 id；无变化时为 null。
                境界只能使用：凡俗、引气、筑基、金丹、元婴、化神、炼虚、合道。境界属于 player.realm，不是功法或天赋；不得创建名为“练气一层/筑基境”等境界状态的 abilityChange。
                只要剧情明确完成突破，必须在同一裁决的 playerPatch.realm 写入新境界；修炼尝试、获得功法或短暂借力不等于突破。
                elapsed 为经过分钟，范围 1-2880，并与章节实际经过时间一致。outcome 只能是 CONTINUE 或 DEAD。
                只有主角被明确判定死亡时才能返回 DEAD；此时 playerPatch.vitality 必须为 0，choices 必须为空数组。
                CONTINUE 时 signals 1-3 条，choices 2-4 个；选择必须是有实质差异的关键方向。不得用梦境、回档、轮回或重生撤销死亡。
                玩家陈述“我天生拥有/从小可以”等持久设定时必须裁决，不能只在剧情中口头承认：
                - 第0至3回合允许补完最多一个低阶先天天赋；必须有限制、代价，且不得冲突既有命格。
                - 第3回合后或强度失衡时必须 REJECTED，除非本轮事件确实构成觉醒来源。
                - ACCEPTED/MODIFIED 的 ABILITY 声明必须同时返回 abilityChange=LEARN；否则裁决无效。
                - REJECTED 时剧情必须明确表现为能力未成立，不能暗中沿用。
                - 剧情中任何新获得的持久天赋、功法或法术都必须同步 abilityChange。
                玩家明确施展已有能力时，Java会扣除使用次数；不要为扣次数单独返回 abilityChange，除非能力本身升级或永久改变。
                必须输出以下 json 格式：
                {
                  "sceneId":"ascii-kebab-id","sceneTitle":"","currentLocation":"","elapsed":30,"outcome":"CONTINUE",
                  "actionResolution":{"result":"ACCEPTED|REJECTED|CLARIFY","title":"","reason":"","suggestions":["",""]},
                  "narrative":["",""],"signals":[""],
                  "choices":[{"id":"ascii-kebab-id","label":"","hint":"","tone":"default"}],
                  "playerPatch":{"vitality":60,"silver":20,"insight":7,"suspicion":30},
                  "event":{"title":"","detail":"","kind":"decision"},
                  "characterPatch":{"id":"existing-id","values":{"state":"","location":"","relation":""}},
                  "threadPatch":{"id":"existing-id","values":{"state":"","danger":60}},
                  "inventoryChange":{"operation":"ADD|REMOVE|UPDATE","id":"ascii-id","definitionId":"ascii-id","name":"","category":"MATERIAL","quality":"凡物","quantity":1,"description":"","keyItem":false},
                  "abilityChange":{"operation":"LEARN|UPDATE","id":"ascii-id","definitionId":"ascii-id","name":"","type":"TALENT|TECHNIQUE|SPELL","stage":"入门","mastery":1,"source":"","description":"","rules":"","maxUses":1,"remainingUses":1,"resetRule":"DAILY|NONE","sideEffect":""},
                  "declarationResolution":{"type":"ABILITY|BACKGROUND|NONE","result":"ACCEPTED|MODIFIED|REJECTED|NOT_APPLICABLE","reason":""}
                }
                没有物品或能力变化时 inventoryChange/abilityChange 必须为 null；每轮最多各变化一项。
                kind 只能是 decision、risk、gain、world；tone 只能是 default 或 danger。
                """;
    }

    private String difficultyInstruction(GameState state) {
        return "HARD".equals(state.difficulty)
                ? "困难：资源与善意更稀缺，失败后果更持久；仍须给出清晰因果与预警，不能无故处死主角。"
                : "普通：保持世界真实，但优先提供可理解的转圜、补救和成长机会，适合休闲沉浸阅读。";
    }

    AgentResult sanitize(AgentResult raw, GameState state) {
        if (raw == null) throw new IllegalArgumentException("Agent 裁决为空");
        AgentResult.ActionResolution actionResolution = sanitizeActionResolution(raw.actionResolution());
        if (!"ACCEPTED".equals(actionResolution.result())) {
            return new AgentResult(
                    state.sceneId, state.sceneTitle, state.currentLocation, 0, "CONTINUE",
                    List.of(), List.of(), List.of(), Map.of(), null, null, null, null, null,
                    actionResolution, sanitizeDeclaration(raw.declarationResolution(), raw.abilityChange())
            );
        }
        String outcome = "DEAD".equalsIgnoreCase(raw.outcome()) ? "DEAD" : "CONTINUE";
        List<String> narrative = strings(raw.narrative(), "DEAD".equals(outcome) ? 3 : 6, 16);
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
        AgentResult.AbilityChange abilityChange = sanitizeAbility(raw.abilityChange(), state);
        Map<String, Object> playerPatch = RealmProgression.completePatch(state,
                sanitizeMap(raw.playerPatch(), PLAYER_KEYS), narrative, signals, abilityChange);
        return new AgentResult(
                safeId(raw.sceneId(), state.sceneId), value(raw.sceneTitle(), state.sceneTitle),
                value(raw.currentLocation(), state.currentLocation), clamp(raw.elapsed(), 1, 2880),
                outcome, narrative, signals, "DEAD".equals(outcome) ? List.of() : choices,
                playerPatch,
                new AgentResult.EventDraft(event.title().strip(), event.detail().strip(), kind),
                sanitizeEntity(raw.characterPatch(), state.characters.stream().map(character -> character.id).toList(), CHARACTER_KEYS),
                sanitizeEntity(raw.threadPatch(), state.threads.stream().map(thread -> thread.id).toList(), THREAD_KEYS),
                sanitizeInventory(raw.inventoryChange(), state), abilityChange,
                actionResolution,
                sanitizeDeclaration(raw.declarationResolution(), raw.abilityChange())
        );
    }

    Map<String, Object> currentStateContext(GameState state) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("phase", state.phase);
        context.put("sceneId", state.sceneId);
        context.put("sceneTitle", state.sceneTitle);
        context.put("currentLocation", state.currentLocation);
        context.put("worldYear", state.worldYear);
        context.put("day", state.day);
        context.put("minute", state.minute);
        context.put("turn", state.turn);
        context.put("world", state.world);
        context.put("player", state.player);
        context.put("currentNarrative", state.narrative.stream().limit(2).toList());
        context.put("signals", state.signals);
        context.put("choices", state.choices);
        context.put("characters", state.characters);
        context.put("threads", state.threads);
        context.put("inventory", state.inventory);
        context.put("abilities", state.abilities);
        context.put("recentEvents", state.events.stream().limit(6).toList());
        return context;
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
            AgentResult.DeclarationResolution declaration, AgentResult.AbilityChange abilityChange) {
        if (declaration == null) {
            return new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "本轮没有玩家设定声明");
        }
        String type = Set.of("ABILITY", "BACKGROUND", "NONE").contains(declaration.type())
                ? declaration.type() : "NONE";
        String result = Set.of("ACCEPTED", "MODIFIED", "REJECTED", "NOT_APPLICABLE").contains(declaration.result())
                ? declaration.result() : "NOT_APPLICABLE";
        if ("ABILITY".equals(type) && Set.of("ACCEPTED", "MODIFIED").contains(result)
                && (abilityChange == null || !"LEARN".equals(abilityChange.operation()))) {
            throw new IllegalArgumentException("Agent承认了新天赋但没有同步能力变更");
        }
        return new AgentResult.DeclarationResolution(type, result, value(declaration.reason(), "已按世界规则裁决"));
    }

    private AgentResult.InventoryChange sanitizeInventory(AgentResult.InventoryChange change, GameState state) {
        if (change == null || !Set.of("ADD", "REMOVE", "UPDATE").contains(change.operation())) return null;
        boolean existing = state.inventory.stream().anyMatch(item -> item.id.equals(change.id()));
        if (("REMOVE".equals(change.operation()) || "UPDATE".equals(change.operation())) && !existing) return null;
        String id = safeId(change.id(), "item-generated");
        return new AgentResult.InventoryChange(change.operation(), id,
                safeId(change.definitionId(), id), value(change.name(), "未知物品"),
                value(change.category(), "MATERIAL"), value(change.quality(), "凡物"),
                clamp(change.quantity(), 1, 9999), value(change.description(), "尚未鉴定。"), change.keyItem());
    }

    private AgentResult.AbilityChange sanitizeAbility(AgentResult.AbilityChange change, GameState state) {
        if (change == null || !Set.of("LEARN", "UPDATE").contains(change.operation())) return null;
        boolean existing = state.abilities.stream().anyMatch(item -> item.id.equals(change.id()));
        if ("UPDATE".equals(change.operation()) && !existing) return null;
        String id = safeId(change.id(), "ability-generated");
        return new AgentResult.AbilityChange(change.operation(), id,
                safeId(change.definitionId(), id), value(change.name(), "未知能力"),
                Set.of("TALENT", "TECHNIQUE", "SPELL").contains(change.type()) ? change.type() : "TECHNIQUE",
                value(change.stage(), "入门"), clamp(change.mastery(), 0, 100),
                value(change.source(), "剧情领悟"), value(change.description(), "尚未完全理解。"),
                value(change.rules(), "依照能力描述生效"), clamp(change.maxUses(), 0, 99),
                clamp(change.remainingUses(), 0, Math.max(0, clamp(change.maxUses(), 0, 99))),
                Set.of("DAILY", "NONE").contains(change.resetRule()) ? change.resetRule() : "NONE",
                value(change.sideEffect(), "无"));
    }

    private List<GameState.StoryChoice> sanitizeChoices(List<GameState.StoryChoice> source) {
        if (source == null) return List.of();
        List<GameState.StoryChoice> result = new ArrayList<>();
        for (int index = 0; index < Math.min(4, source.size()); index++) {
            GameState.StoryChoice choice = source.get(index);
            if (choice == null || blank(choice.label) || blank(choice.hint)) continue;
            result.add(new GameState.StoryChoice(
                    safeId(choice.id, "choice-" + (index + 1)), choice.label.strip(), choice.hint.strip(),
                    "danger".equals(choice.tone) ? "danger" : "default"
            ));
        }
        return result;
    }

    private AgentResult.EntityPatch sanitizeEntity(AgentResult.EntityPatch patch, List<String> ids, Set<String> allowedKeys) {
        if (patch == null || !ids.contains(patch.id())) return null;
        Map<String, Object> values = sanitizeMap(patch.values(), allowedKeys);
        return values.isEmpty() ? null : new AgentResult.EntityPatch(patch.id(), values);
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source, Set<String> allowedKeys) {
        if (source == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (allowedKeys.contains(key) && value != null) result.put(key, value);
        });
        return result;
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
