package com.wenxian.game.story.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatchValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用中性模型网关生成普通回合的剧情和 StoryPatch。
 *
 * <p>上下文按客观事实、认知、记忆、历史叙事和玩家行动分层序列化。模型首次返回无法解析或违反通用
 * 结构约束时，只允许一次不携带完整历史的格式修复；两次都失败则拒绝本轮，数据库和玩家回合保持不变。</p>
 */
@Service
public class ModelBackedStoryTurnAgent implements StoryTurnAgent {
    private static final String SYSTEM_PROMPT = """
            你是《问仙录》的回合叙事与变化提案 Agent。你可以自由演绎世界，但模型输出不是事实，服务端会校验后提交。

            严格遵守以下边界：
            1. objective 是当前唯一客观事实；cognition 是人物所知或所信；memory、narrative 只是叙事材料；
               action 是玩家本轮授权。不得用摘要、旧正文或人物观点静默覆盖 objective。
            2. 只执行 action 已授权的行动及其直接后果。来到新的重大、不可逆决定前必须停止，
               decisionCheckpoint.required=true，并把决定交还玩家；不得替玩家加入宗门、立誓、结盟、屠杀、放弃传承等。
               objective.pendingDecision 不为空时，本轮 action 是玩家对该问题的回应；若 action 没有明确回答，必须继续返回
               相同的 required=true 和 prompt，不能擅自清除、跳过或替玩家决定。
            3. 已有实体只能用 objective 提供的稳定 entityId 更新。不能按名字猜测，不能为了绕过死亡、掌握、数量或归属状态
               创建同名实体。没有在 objective 出现的状态，不得声称它已被系统确认。
            4. 物品数量已知时，使用、出售、赠予或获得后必须返回准确的新总量；数量未知就保持 known=false、value=null，
               不得自动记为1。能力的 MASTERED 与 TEMPORARILY_BLOCKED 分开，受限不等于未掌握。
            5. 人物为 DEAD 时不得无依据复活。若 action 明确允许修订且有充分依据，生命周期变化必须写 changeReason。
            6. time 必须写本轮实际经过分钟。玩家明确说一月、三年等时必须完整换算；世界未另定历法时按一年360日、
               一月30日计算。若被打断，resolution=INTERRUPTED，并返回实际时长与 interruptionReason，正文必须一致。
            7. 玩家明确修订世界设定时使用 worldFacts.REVISE 并引用 factId、保留 reason；新增空白设定使用 EXPAND。
               普通剧情中的局势发展使用 TRANSFORM，不得把旧回合正文改写成从未发生。
            8. 正文始终用第二人称“你”指代主角，不在第一、第三人称之间切换。通常写6至10段、约800至1200个中文字符；
               以完成本轮行动和保持沉浸为准，不为凑字重复。正文结束在下一次真正需要玩家决定的位置。
            9. 返回2至4个彼此有意义的行动建议；它们只是建议，不能代表玩家已经选择。

            只返回一个 JSON 对象，不要 Markdown、解释或额外文字。完整字段如下，未变化的对象字段返回 null，未使用列表返回 []：
            {
              "title":"本回合标题","narrative":"第二人称剧情正文",
              "time":{"elapsedMinutes":0,"resolution":"COMPLETE","interruptionReason":null},
              "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
              "entities":[{"operation":"CREATE|UPDATE|HIDE|RESTORE","entityId":null,"entityType":"ITEM|ABILITY|CHARACTER或上下文已有类型",
                "name":null,"status":null,"summary":null,"attributes":null,
                "quantity":{"known":true,"value":1},"inPossession":true,
                "abilityState":"MASTERED|TEMPORARILY_BLOCKED|LOST|UNKNOWN",
                "lifecycle":{"state":"ACTIVE|MISSING|DORMANT|DEAD|UNKNOWN","changeReason":null}}],
              "worldFacts":[{"operation":"EXPAND|TRANSFORM|REVISE","factId":null,"statement":"","reason":null}],
              "knowledge":[{"subjectRef":"","topicRef":"","summary":"","evidenceRefs":[]}],
              "memories":[{"memoryType":"EVENT|PROMISE|CLUE|RELATIONSHIP","title":"","summary":"","importance":"KEY|NORMAL","evidenceRefs":[]}],
              "decisionCheckpoint":{"required":false,"prompt":null},
              "choices":[{"title":"","description":""}],"rollingSummary":""
            }
            """;

    private static final String REPAIR_PROMPT = """
            下面是你刚才返回但未通过 JSON/结构校验的结果。只修复格式和契约字段，不新增剧情、事实或决定，
            不需要重新读取完整上下文。只返回修复后的单个 JSON 对象，不要 Markdown。
            """;

    private final StoryModelGateway gateway;
    private final StoryModelInvocationRecorder invocations;
    private final ObjectMapper mapper;
    private final ObjectMapper tolerantMapper;
    private final StoryPatchValidator validator;

    @Autowired
    public ModelBackedStoryTurnAgent(StoryModelGateway gateway, StoryModelInvocationRecorder invocations,
                                     ObjectMapper mapper) {
        this(gateway, invocations, mapper, new StoryPatchValidator());
    }

    public ModelBackedStoryTurnAgent(StoryModelGateway gateway, ObjectMapper mapper) {
        this(gateway, StoryModelInvocationRecorder.NOOP, mapper, new StoryPatchValidator());
    }

    ModelBackedStoryTurnAgent(StoryModelGateway gateway, StoryModelInvocationRecorder invocations,
                              ObjectMapper mapper, StoryPatchValidator validator) {
        this.gateway = gateway;
        this.invocations = invocations == null ? StoryModelInvocationRecorder.NOOP : invocations;
        this.mapper = mapper;
        this.tolerantMapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        this.validator = validator;
    }

    @Override
    public Generated generate(StoryPromptContext context, StoryModelRoute route) {
        return generate(context, route, null);
    }

    @Override
    public Generated generate(StoryPromptContext context, StoryModelRoute route, InvocationContext invocation) {
        if (context == null) throw new IllegalArgumentException("回合推演缺少命卷上下文");
        if (context.action() == null || context.action().playerAction() == null
                || context.action().playerAction().isBlank()) {
            throw new IllegalArgumentException("玩家行动不能为空");
        }
        if (route == null) throw new IllegalArgumentException("回合推演缺少模型路由");

        List<StoryModelGateway.Result> attempts = new ArrayList<>();
        StoryModelGateway.Result first = call(route, invocation, "STORY_TURN",
                SYSTEM_PROMPT, userPrompt(context), 0.7);
        attempts.add(first);
        try {
            return new Generated(parse(first.text()), attempts);
        } catch (JsonProcessingException | IllegalArgumentException firstError) {
            StoryModelGateway.Result repaired = call(route, invocation, "STORY_TURN_REPAIR", SYSTEM_PROMPT,
                    repairPrompt(first.text(), firstError.getMessage()), 0.1);
            attempts.add(repaired);
            try {
                return new Generated(parse(repaired.text()), attempts);
            } catch (JsonProcessingException | IllegalArgumentException repairedError) {
                throw new IllegalArgumentException("推演结果未能形成可提交的状态提案", repairedError);
            }
        }
    }

    private StoryModelGateway.Result call(StoryModelRoute route, InvocationContext invocation, String operation,
                                          String systemPrompt, String userPrompt, double temperature) {
        long started = System.nanoTime();
        try {
            StoryModelGateway.Result result = gateway.generate(new StoryModelGateway.Request(
                    route.baseUrl(), route.apiKey(), route.provider(), route.adapterType(), route.model(),
                    systemPrompt, userPrompt, temperature));
            invocations.succeeded(invocation, operation, route, result);
            return result;
        } catch (RuntimeException failure) {
            invocations.failed(invocation, operation, route,
                    (System.nanoTime() - started) / 1_000_000L, failure);
            throw failure;
        }
    }

    private StoryPatch parse(String raw) throws JsonProcessingException {
        StoryPatch patch = tolerantMapper.readValue(rawJson(raw), StoryPatch.class);
        validator.validate(patch);
        return patch;
    }

    private String userPrompt(StoryPromptContext context) {
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("systemConstraints", context.system());
        sections.put("objective", context.objective());
        sections.put("cognition", context.cognition());
        sections.put("memory", context.memory());
        sections.put("narrative", context.narrative());
        sections.put("action", context.action());
        try {
            return mapper.writeValueAsString(sections);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("回合推演上下文无法序列化", error);
        }
    }

    private String repairPrompt(String invalidOutput, String problem) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("validationProblem", problem == null ? "结构校验失败" : problem);
        payload.put("invalidOutput", invalidOutput == null ? "" : invalidOutput);
        try {
            return REPAIR_PROMPT + "\n" + mapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("格式修复请求无法序列化", error);
        }
    }

    private String rawJson(String raw) {
        if (raw == null) return "{}";
        String value = raw.strip();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) value = value.substring(firstLine + 1, lastFence).strip();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }
}
