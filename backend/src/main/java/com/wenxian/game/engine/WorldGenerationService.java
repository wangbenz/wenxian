package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class WorldGenerationService {
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public WorldGenerationService(ObjectMapper objectMapper,
                                  RestClient.Builder restClientBuilder,
                                  @Value("${deepseek.api-key:}") String apiKey,
                                  @Value("${deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                                  @Value("${deepseek.model:deepseek-v4-pro}") String model) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public GameState generateFates(String saveId, String playerName, String race,
                                   String gender, String lifeGoal, String preference) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DeepSeek Agent 尚未配置");
        try {
            String systemPrompt = """
                    你是《问仙录》的择命 Agent。只输出 JSON，不要 Markdown，不要解释。
                    玄墟界是固定世界：绝天之变后三千年，飞升真伪不明；修行需要资质、资源、传承、时间和代价；NPC 不围绕主角静止；死亡不可自动回档。
                    不得生成新的世界、种族或境界体系。你需要为同一名玩家生成三种互不相同的凡俗开局命格。
                    三个候选人的 name、race、gender、lifeGoal 必须与用户输入完全一致，但出身、缺陷、困境与开场不同。
                    主角尚未正式修炼，不得赠送高阶功法、法宝、重生记忆或无条件外挂。文本使用简体中文。
                    数值范围：age 14-30，vitality 45-85，silver 0-35，insight 3-9，suspicion 5-60，danger 20-90。
                    JSON 格式：
                    {"protagonists":[{
                      "id":"ascii-kebab-id","name":"","race":"","gender":"","lifeGoal":"","age":16,
                      "identity":"","origin":"","trait":"","goal":"","burden":"","constitution":"",
                      "vitality":60,"silver":20,"insight":7,"suspicion":30,
                      "opening":{"sceneId":"ascii-kebab-id","sceneTitle":"","location":"","day":1,"minute":480,
                        "narrative":["","",""],"signals":["",""],
                        "choices":[{"id":"ascii-kebab-id","label":"","hint":"","tone":"default"}],
                        "characters":[{"id":"ascii-kebab-id","name":"","identity":"","state":"","location":"","relation":"","lastSeenAt":480}],
                        "threads":[{"id":"ascii-kebab-id","title":"","state":"","danger":50}]
                      }
                    }]}
                    protagonists 必须恰好3个；每个开场恰好3个choices、2至4个characters、2至3个threads，并至少有一个danger选项。
                    """;
            String prompt = """
                    主角姓名：%s
                    种族代码：%s
                    性别代码：%s
                    人生倾向代码：%s
                    额外偏好：%s
                    """.formatted(playerName, race, gender, lifeGoal,
                    preference == null || preference.isBlank() ? "残酷、克制、低起点" : preference.strip());
            String content = requestJson(systemPrompt, prompt, 3800, 0.86);
            FateDraft draft = objectMapper.readValue(content, FateDraft.class);
            return normalize(saveId, playerName, race, gender, lifeGoal, draft);
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DeepSeek 命格生成失败", error);
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }

    public List<GameState.AbilityRecord> generateTalentOptions(GameState state) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DeepSeek Agent 尚未配置");
        try {
            int count = 1 + Math.floorMod(UUID.randomUUID().hashCode(), 3);
            String systemPrompt = """
                    你是《问仙录》的先天天赋 Agent。只输出 JSON，不要 Markdown。
                    为刚选定命格的凡俗主角生成 %d 个可选的低阶先天天赋。天赋要有爽感、易于代入，但不能直接赠送修为、高阶功法、法宝、重生记忆或无条件无敌能力。
                    必须根据种族、出身、性格、体质和人生所求生成，并各有一个清晰限制或轻微代价。
                    description 是直接展示给玩家的小说化描述：只写感受、征兆、擅长之处与可能的隐患，不得出现百分比、概率、冷却、回合、数值加成、系统提示、触发次数等游戏术语。
                    rules、maxUses、resetRule、sideEffect 仅供后端裁决，不会展示给玩家；rules 必须可执行，但同样不要使用百分比数值，优先使用自然条件和叙事后果约束能力。
                    JSON: {"talents":[{"id":"ascii-kebab-id","name":"","description":"","rules":"","maxUses":1,"resetRule":"DAILY|NONE","sideEffect":""}]}
                    talents 数量必须恰好为 %d；name 2-6字；description 45-100字；rules 必须可执行；maxUses 0-2。
                    """.formatted(count, count);
            String player = objectMapper.writeValueAsString(Map.of(
                    "name", state.player.name, "race", state.player.race,
                    "identity", state.player.identity, "lifeGoal", state.player.lifeGoal,
                    "goal", state.player.goal, "burden", state.player.burden,
                    "constitution", state.player.constitution));
            TalentDraft draft = objectMapper.readValue(requestJson(systemPrompt, player, 1800, 0.9), TalentDraft.class);
            if (draft == null || draft.talents == null || draft.talents.size() < count) {
                throw new IllegalArgumentException("天赋候选不足");
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            List<GameState.AbilityRecord> result = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                TalentOption option = draft.talents.get(index);
                String id = uniqueId(ids, safeId(option.id, "innate-talent-" + (index + 1)));
                int uses = clamp(option.maxUses, 0, 2);
                String reset = uses > 0 && "DAILY".equals(option.resetRule) ? "DAILY" : "NONE";
                result.add(new GameState.AbilityRecord(id, id, required(option.name, "灵觉"), "TALENT", "初醒", 1,
                        "命格觉醒", required(option.description, "对周遭异常比常人更敏锐。"),
                        required(option.rules, "需要主动集中精神才能发挥。"), uses, uses, reset, state.day,
                        required(option.sideEffect, "过度使用会精神疲惫。")));
            }
            return result;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "天赋推演暂时中断，请重新推演", error);
        }
    }

    public String mode() {
        return configured() ? "deepseek:" + model : "deepseek-not-configured";
    }

    public String model() {
        return model;
    }

    public String requestJson(String systemPrompt, String userPrompt, int maxTokens, double temperature) {
        if (!configured()) throw new IllegalStateException("尚未配置 DeepSeek API Key");
        Map<String, Object> request = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "thinking", Map.of("type", "disabled"),
                "response_format", Map.of("type", "json_object"),
                "temperature", temperature,
                "max_tokens", maxTokens,
                "stream", false
        );
        JsonNode response = restClient.post().uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(request).retrieve().body(JsonNode.class);
        if (response == null) throw new IllegalStateException("DeepSeek 返回为空");
        String content = response.path("choices").path(0).path("message").path("content").asText();
        if (content.isBlank()) throw new IllegalStateException("DeepSeek JSON 内容为空");
        return content;
    }

    public ChatResponse requestMessage(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                       int maxTokens, double temperature) {
        if (!configured()) throw new IllegalStateException("尚未配置 DeepSeek API Key");
        Map<String, Object> request = Map.of(
                "model", model,
                "messages", messages,
                "tools", tools,
                "tool_choice", "auto",
                "thinking", Map.of("type", "disabled"),
                "response_format", Map.of("type", "json_object"),
                "temperature", temperature,
                "max_tokens", maxTokens,
                "stream", false
        );
        JsonNode response = restClient.post().uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .body(request).retrieve().body(JsonNode.class);
        if (response == null) throw new IllegalStateException("DeepSeek 返回为空");
        JsonNode message = response.path("choices").path(0).path("message");
        if (message.isMissingNode() || message.isNull()) throw new IllegalStateException("DeepSeek 消息为空");
        JsonNode usage = response.path("usage");
        return new ChatResponse(message, response.path("id").asText(""),
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
    }

    public record ChatResponse(JsonNode message, String responseId, int promptTokens, int completionTokens) {}

    private GameState normalize(String saveId, String playerName, String race,
                                String gender, String lifeGoal, FateDraft draft) {
        if (draft == null || draft.protagonists == null || draft.protagonists.size() < 3) {
            throw new IllegalArgumentException("命格生成结果不足三个");
        }
        GameState setup = new GameState();
        setup.saveId = saveId;
        setup.phase = "setup";
        setup.sceneId = "choose-fate";
        setup.sceneTitle = "择命";
        setup.currentLocation = "问仙录";
        setup.worldYear = 3001;
        setup.day = 1;
        setup.minute = 8 * 60;
        setup.world = GameCatalog.fixedWorld();

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < 3; index++) {
            GameState.ProtagonistOption option = draft.protagonists.get(index);
            if (option == null || option.opening == null) throw new IllegalArgumentException("命格开场缺失");
            option.id = uniqueId(ids, safeId(option.id, "fate-" + (index + 1)));
            option.name = playerName;
            option.race = race;
            option.gender = gender;
            option.lifeGoal = lifeGoal;
            option.age = clamp(option.age, 14, 30);
            option.identity = required(option.identity, "无名凡人");
            option.origin = required(option.origin, "来历不明");
            option.trait = required(option.trait, "谨慎");
            option.goal = required(option.goal, "活下去");
            option.burden = required(option.burden, "尚未偿还的旧债");
            option.constitution = required(option.constitution, "资质未测");
            option.vitality = clamp(option.vitality, 1, 100);
            option.silver = clamp(option.silver, 0, 999);
            option.insight = clamp(option.insight, 1, 10);
            option.suspicion = clamp(option.suspicion, 0, 100);
            normalizeOpening(option.opening, index);
            setup.protagonistOptions.add(option);
        }
        return setup;
    }

    private void normalizeOpening(GameState.OpeningSeed opening, int index) {
        opening.sceneId = safeId(opening.sceneId, "opening-" + (index + 1));
        opening.sceneTitle = required(opening.sceneTitle, "命运初启");
        opening.location = required(opening.location, "东陆边城");
        opening.day = Math.max(1, opening.day);
        opening.minute = clamp(opening.minute, 0, 1439);
        opening.narrative = limit(nonBlank(opening.narrative), 4);
        opening.signals = limit(nonBlank(opening.signals), 3);
        if (opening.narrative.size() < 2 || opening.choices == null || opening.choices.size() < 3) {
            throw new IllegalArgumentException("命格开场内容不足");
        }
        LinkedHashSet<String> choiceIds = new LinkedHashSet<>();
        List<GameState.StoryChoice> choices = new ArrayList<>();
        for (int choiceIndex = 0; choiceIndex < 3; choiceIndex++) {
            GameState.StoryChoice choice = opening.choices.get(choiceIndex);
            if (choice == null) throw new IllegalArgumentException("命格选项缺失");
            choice.id = uniqueId(choiceIds, safeId(choice.id, "choice-" + (choiceIndex + 1)));
            choice.label = required(choice.label, "继续观察");
            choice.hint = required(choice.hint, "局势仍不明朗");
            choice.tone = "danger".equals(choice.tone) ? "danger" : "default";
            choices.add(choice);
        }
        opening.choices = choices;
        opening.characters = opening.characters == null ? new ArrayList<>() : limit(opening.characters, 5);
        opening.threads = opening.threads == null ? new ArrayList<>() : limit(opening.threads, 4);
        for (int i = 0; i < opening.characters.size(); i++) {
            GameState.CharacterRecord character = opening.characters.get(i);
            character.id = safeId(character.id, "character-" + (index + 1) + "-" + (i + 1));
            character.name = required(character.name, "无名者");
            character.identity = required(character.identity, "身份未知");
            character.state = required(character.state, "存活");
            character.location = required(character.location, opening.location);
            character.relation = required(character.relation, "尚未判断");
            character.lastSeenAt = opening.minute;
        }
        for (int i = 0; i < opening.threads.size(); i++) {
            GameState.QuestThread thread = opening.threads.get(i);
            thread.id = safeId(thread.id, "thread-" + (index + 1) + "-" + (i + 1));
            thread.title = required(thread.title, "未解之事");
            thread.state = required(thread.state, "尚无进展");
            thread.danger = clamp(thread.danger, 0, 100);
        }
    }

    private static String safeId(String value, String fallback) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? fallback : normalized.substring(0, Math.min(64, normalized.length()));
    }

    private static String uniqueId(LinkedHashSet<String> ids, String base) {
        String candidate = base;
        int suffix = 2;
        while (!ids.add(candidate)) candidate = base + "-" + suffix++;
        return candidate;
    }

    private static String required(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static List<String> nonBlank(List<String> values) {
        if (values == null) return new ArrayList<>();
        return values.stream().filter(value -> value != null && !value.isBlank()).map(String::strip).toList();
    }

    private static <T> ArrayList<T> limit(List<T> values, int maximum) {
        return new ArrayList<>(values.subList(0, Math.min(maximum, values.size())));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static class FateDraft {
        public List<GameState.ProtagonistOption> protagonists;
        public FateDraft() {}
    }

    public static class TalentDraft {
        public List<TalentOption> talents;
        public TalentDraft() {}
    }

    public static class TalentOption {
        public String id;
        public String name;
        public String description;
        public String rules;
        public int maxUses;
        public String resetRule;
        public String sideEffect;
        public TalentOption() {}
    }
}
