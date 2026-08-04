package com.wenxian.game.v014.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
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
    private final V014ModelGateway gateway;
    private final V014PromptAssembler prompts;
    private final ObjectMapper mapper;
    private final AiUsageStore usage;

    public V014StoryAgent(V014ModelGateway gateway, V014PromptAssembler prompts, ObjectMapper mapper,
                          AiUsageStore usage) {
        this.gateway = gateway;
        this.prompts = prompts;
        this.mapper = mapper;
        this.usage = usage;
    }

    public Generated generate(String userId, V014State state, String summary, List<RecentTurn> recent,
                              String action, ResolvedRoute route) {
        if (route.apiKey() == null || route.apiKey().isBlank()) {
            throw GameApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_KEY_UNAVAILABLE", "SYSTEM",
                    "推演服务尚未就绪", "当前没有可用的推演密钥。", List.of("稍后再试", "在设置中接入自己的密钥"));
        }
        V014PromptAssembler.Prompt prompt = prompts.assemble(state, summary, recent, action);
        try {
            V014ModelGateway.Result result = gateway.generate(new V014ModelGateway.Request(
                    route.baseUrl(), route.apiKey(), route.provider(), route.adapterType(), route.model(),
                    prompt.system(), prompt.user(), 0.75));
            AiTurnEnvelope envelope = parse(result.text());
            usage.recordVersion("V0.14", state.saveId(), state.turnNumber() + 1, "V014_STORY",
                    route.provider(), route.credentialSource(), route.model(), "SUCCESS",
                    result.inputTokens(), result.outputTokens(), result.latencyMs(), "");
            return new Generated(envelope, result, rawPatch(result.text()));
        } catch (GameApiException error) {
            throw error;
        } catch (RuntimeException error) {
            usage.recordVersion("V0.14", state.saveId(), state.turnNumber() + 1, "V014_STORY",
                    route.provider(), route.credentialSource(), route.model(), "FAILED", 0, 0, 0, error.getMessage());
            throw GameApiException.temporary("STORY_GENERATION_FAILED", "本次推演未能完成",
                    "推演服务暂时没有完成这一轮，本次不会消耗回合或改变命卷。",
                    List.of("稍后重新尝试", "更换模型后再试"), error.getMessage(), error);
        }
    }

    private AiTurnEnvelope parse(String raw) {
        String json = rawPatch(raw);
        try {
            AiTurnEnvelope parsed = mapper.readValue(json, AiTurnEnvelope.class);
            if (parsed.narrative() != null && !parsed.narrative().isBlank()) return parsed;
        } catch (JsonProcessingException ignored) {
            // A usable narrative is more valuable than retrying an otherwise successful model call.
        }
        String text = raw == null ? "" : raw.strip();
        if (text.isBlank()) throw new IllegalStateException("模型没有返回正文");
        return new AiTurnEnvelope("命卷新页", text, 60L, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), "");
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
}
