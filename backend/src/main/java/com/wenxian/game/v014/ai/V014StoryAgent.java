package com.wenxian.game.v014.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.persistence.AiUsageStore;
import com.wenxian.game.v014.model.AiTurnEnvelope;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.persistence.V014TurnStore.RecentTurn;
import com.wenxian.game.v014.service.V014ModelPreferenceService.ResolvedRoute;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class V014StoryAgent {
    private static final int MAX_REPAIR_SOURCE_CHARS = 16_000;
    private final V014ModelGateway gateway;
    private final V014PromptAssembler prompts;
    private final ObjectMapper mapper;
    private final ObjectMapper tolerantMapper;
    private final AiUsageStore usage;

    public V014StoryAgent(V014ModelGateway gateway, V014PromptAssembler prompts, ObjectMapper mapper,
                          AiUsageStore usage) {
        this.gateway = gateway;
        this.prompts = prompts;
        this.mapper = mapper;
        this.tolerantMapper = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
        this.usage = usage;
    }

    public Generated generate(String userId, V014State state, String summary, List<RecentTurn> recent,
                              String action, ResolvedRoute route) {
        if (route.apiKey() == null || route.apiKey().isBlank()) {
            throw GameApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_KEY_UNAVAILABLE", "SYSTEM",
                    "推演服务尚未就绪", "当前没有可用的推演密钥。", List.of("稍后再试", "在设置中接入自己的密钥"));
        }
        V014PromptAssembler.Prompt prompt = prompts.assemble(state, summary, recent, action);
        V014ModelGateway.Result initial;
        try {
            initial = gateway.generate(new V014ModelGateway.Request(
                    route.baseUrl(), route.apiKey(), route.provider(), route.adapterType(), route.model(),
                    prompt.system(), prompt.user(), 0.75));
        } catch (RuntimeException error) {
            usage.recordVersion("V0.14", state.saveId(), state.turnNumber() + 1, "V014_STORY",
                    route.provider(), route.credentialSource(), route.model(), "FAILED", 0, 0, 0,
                    "MODEL_REQUEST_FAILED");
            throw generationFailed("推演服务暂时没有完成这一轮", error);
        }

        try {
            AiTurnEnvelope envelope = parse(initial.text());
            usage.recordVersion("V0.14", state.saveId(), state.turnNumber() + 1, "V014_STORY",
                    route.provider(), route.credentialSource(), route.model(), "SUCCESS",
                    initial.inputTokens(), initial.outputTokens(), initial.latencyMs(), "");
            return new Generated(envelope, initial, normalized(envelope));
        } catch (ResponseFormatException invalid) {
            usage.recordVersion("V0.14", state.saveId(), state.turnNumber() + 1, "V014_STORY",
                    route.provider(), route.credentialSource(), route.model(), "FAILED",
                    initial.inputTokens(), initial.outputTokens(), initial.latencyMs(), "FORMAT_INVALID");
            return repair(state, route, initial, invalid);
        }
    }

    private AiTurnEnvelope parse(String raw) {
        String json = rawPatch(raw);
        try {
            AiTurnEnvelope parsed = tolerantMapper.readValue(json, AiTurnEnvelope.class);
            if (parsed.narrative() != null && !parsed.narrative().isBlank()) return parsed;
            throw new ResponseFormatException("模型没有返回正文", null);
        } catch (JsonProcessingException error) {
            throw new ResponseFormatException("模型返回的结构不完整", error);
        }
    }

    private Generated repair(V014State state, ResolvedRoute route, V014ModelGateway.Result initial,
                             ResponseFormatException initialError) {
        String source = rawPatch(initial.text());
        if (source.length() > MAX_REPAIR_SOURCE_CHARS) source = source.substring(0, MAX_REPAIR_SOURCE_CHARS);
        String system = """
                你只负责修复 JSON 格式，不重新创作剧情。保留原文事实、结果、人物、物品与行动选项，
                将输入整理为一个合法 JSON 对象。narrative 必须是单个字符串，段落用转义后的换行表示。
                只允许字段：title,narrative,elapsedMinutes,location,condition,realm,lifeGoal,choices,
                items,abilities,characters,memories,rollingSummary。缺失的列表使用空数组，不要输出 Markdown 或解释。
                """;
        V014ModelGateway.Result repaired;
        try {
            repaired = gateway.generate(new V014ModelGateway.Request(
                    route.baseUrl(), route.apiKey(), route.provider(), route.adapterType(), route.model(),
                    system, "待修复内容：\n" + source, 0.0));
        } catch (RuntimeException error) {
            usage.recordVersion("V0.14", state.saveId(), state.turnNumber() + 1, "V014_STORY_REPAIR",
                    route.provider(), route.credentialSource(), route.model(), "FAILED", 0, 0, 0,
                    "REPAIR_REQUEST_FAILED");
            throw generationFailed("推演结果整理失败", error);
        }
        try {
            AiTurnEnvelope envelope = parse(repaired.text());
            usage.recordVersion("V0.14", state.saveId(), state.turnNumber() + 1, "V014_STORY_REPAIR",
                    route.provider(), route.credentialSource(), route.model(), "SUCCESS",
                    repaired.inputTokens(), repaired.outputTokens(), repaired.latencyMs(), "");
            return new Generated(envelope, combine(initial, repaired), normalized(envelope));
        } catch (ResponseFormatException error) {
            usage.recordVersion("V0.14", state.saveId(), state.turnNumber() + 1, "V014_STORY_REPAIR",
                    route.provider(), route.credentialSource(), route.model(), "FAILED",
                    repaired.inputTokens(), repaired.outputTokens(), repaired.latencyMs(), "FORMAT_INVALID");
            error.addSuppressed(initialError);
            throw generationFailed("推演结果连续两次无法整理", error);
        }
    }

    private V014ModelGateway.Result combine(V014ModelGateway.Result initial, V014ModelGateway.Result repaired) {
        return new V014ModelGateway.Result(repaired.text(), repaired.responseId(), repaired.model(),
                initial.inputTokens() + repaired.inputTokens(), initial.outputTokens() + repaired.outputTokens(),
                initial.latencyMs() + repaired.latencyMs());
    }

    private String normalized(AiTurnEnvelope envelope) {
        try { return mapper.writeValueAsString(envelope); }
        catch (JsonProcessingException error) { throw new IllegalStateException("推演结果无法保存", error); }
    }

    private GameApiException generationFailed(String diagnostic, RuntimeException error) {
        return GameApiException.temporary("STORY_GENERATION_FAILED", "本次推演未能完成",
                "推演结果没有形成完整命卷，本次不会消耗回合或改变记录。",
                List.of("重新尝试本轮行动", "换一种说法后再试"), diagnostic, error);
    }

    private String rawPatch(String raw) {
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

    public record Generated(AiTurnEnvelope envelope, V014ModelGateway.Result result, String rawPatchJson) {}

    private static final class ResponseFormatException extends RuntimeException {
        private ResponseFormatException(String message, Throwable cause) { super(message, cause); }
    }
}
