package com.wenxian.game.v014.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.persistence.AiUsageStore;
import com.wenxian.game.service.PlayerAiService;
import com.wenxian.game.v014.model.V014ApiModels.CharacterRoute;
import com.wenxian.game.v014.model.V014ApiModels.CharacterRouteRequest;
import com.wenxian.game.v014.model.V014State.WorldProfile;
import com.wenxian.game.v014.service.V014ModelPreferenceService.ResolvedRoute;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class V014CharacterRouteAgent {
    private final V014ModelGateway gateway;
    private final ObjectMapper mapper;
    private final AiUsageStore usage;
    private final PlayerAiService playerAi;

    public V014CharacterRouteAgent(V014ModelGateway gateway, ObjectMapper mapper,
                                   AiUsageStore usage, PlayerAiService playerAi) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.usage = usage;
        this.playerAi = playerAi;
    }

    public List<CharacterRoute> generate(String userId, WorldProfile world, CharacterRouteRequest request,
                                         ResolvedRoute route) {
        if (route.apiKey() == null || route.apiKey().isBlank()) {
            throw GameApiException.of(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_KEY_UNAVAILABLE", "SYSTEM",
                    "推演服务尚未就绪", "当前没有可用的推演密钥。", List.of("稍后再试", "在设置中接入自己的密钥"));
        }
        PlayerAiService.Route quotaRoute = new PlayerAiService.Route(
                new com.wenxian.game.config.RuntimeAiConfigService.RuntimeConfig(
                        route.provider(), route.model(), route.model(), route.apiKey()), route.credentialSource());
        playerAi.assertPublicQuota(userId, quotaRoute);

        String system = """
                你是《问仙录》的角色路线设计者。根据权威世界与玩家选择，生成恰好三条差异明显、都能长期游玩的开局路线。
                origin 是角色不可随意改写的根源事实；startingRealm 是与这条根源一致的开局修行状态。
                玩家可以选择凡人、弱者、宗门强者、妖王后代或爽文式强者开局，不得一律压成最低境界。
                若路线设定角色已经强大，startingRealm 与 opening 必须承认该事实，并提供与其身份相称的局势。
                若根源没有明确修行层次，startingRealm 返回“境界未明”，不要擅自补成凡俗或绝世强者。
                opening 是进入游戏时立即面对的具体处境；preference 是这条路线适合的游玩方向，不是强制主线和必然结局。
                opening 必须使用第二人称“你”描述玩家角色，不得改用第一人称“我”、角色姓名或第三人称“他/她”叙述主角。
                世界与种族应明显影响路线。性别仅在社会身份确有意义时体现，不得套用刻板印象。
                玩家选择的种族是不可改写的硬事实，不得改成混血、隐藏的其他种族或转世身份。
                若为灵族，必须写清由何种器物、草木、山川或执念生出灵智；若为妖族，应写清本相或族裔；人族不必强加神秘血脉。
                不得为官方世界新增或篡改全局结论，例如“仙路已断”“某族注定灭亡”；路线只能补充角色个人经历与局部矛盾。
                三条路线应分别呈现不同的身份、矛盾与探索空间，并给玩家保留自由行动余地。
                如果玩家给出角色设定或游玩偏好种子，应吸收其意图但仍生成三种不同实现方式。
                只返回紧凑 JSON：{\"routes\":[{\"title\":\"\",\"origin\":\"\",\"opening\":\"\",\"preference\":\"\",\"startingRealm\":\"\"}]}。
                startingRealm 是必填字段，不超过十个字；其他字段使用一至两句中文，title 不超过八个字，routes 必须恰好三个。
                """;
        String user = "世界：" + json(world) + "\n角色姓名：" + request.playerName().strip()
                + "\n种族：" + request.race().strip() + "\n性别：" + request.gender().strip()
                + "\n角色设定种子：" + text(request.roleSeed())
                + "\n游玩偏好种子：" + text(request.preferenceSeed());
        try {
            V014ModelGateway.Result result = gateway.generate(new V014ModelGateway.Request(
                    route.baseUrl(), route.apiKey(), route.provider(), route.adapterType(), route.model(), system, user, 0.9));
            List<CharacterRoute> routes = parse(result.text());
            usage.recordVersion("V0.14", null, null, "V014_CHARACTER_ROUTES", route.provider(),
                    route.credentialSource(), route.model(), "SUCCESS", result.inputTokens(), result.outputTokens(),
                    result.latencyMs(), "");
            return routes;
        } catch (GameApiException error) {
            throw error;
        } catch (RuntimeException error) {
            usage.recordVersion("V0.14", null, null, "V014_CHARACTER_ROUTES", route.provider(),
                    route.credentialSource(), route.model(), "FAILED", 0, 0, 0, error.getMessage());
            throw GameApiException.temporary("CHARACTER_ROUTE_GENERATION_FAILED", "角色路线未能完成",
                    "本次推演没有生成可用的角色路线，不会创建存档或消耗游戏回合。",
                    List.of("重新推演", "直接填写角色设定"), error.getMessage(), error);
        }
    }

    List<CharacterRoute> parse(String raw) {
        try {
            RouteEnvelope envelope = mapper.readValue(jsonObject(raw), RouteEnvelope.class);
            if (envelope.routes() == null || envelope.routes().size() != 3) {
                throw new IllegalStateException("模型没有返回三条路线");
            }
            List<CharacterRoute> routes = new ArrayList<>();
            for (int index = 0; index < envelope.routes().size(); index++) {
                RouteDraft route = envelope.routes().get(index);
                routes.add(new CharacterRoute("route-" + (index + 1), required(route.title(), 12),
                        required(route.origin(), 160), required(route.opening(), 240),
                        required(route.preference(), 160), optional(route.startingRealm(), "境界未明", 40)));
            }
            return List.copyOf(routes);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("角色路线格式无法解析", error);
        }
    }

    private String required(String value, int max) {
        if (value == null || value.isBlank()) throw new IllegalStateException("角色路线字段不完整");
        String clean = value.strip();
        return clean.substring(0, Math.min(clean.length(), max));
    }

    private String optional(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.strip();
        return clean.substring(0, Math.min(clean.length(), max));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("世界设定无法组装", error); }
    }

    private String jsonObject(String raw) {
        if (raw == null) return "{}";
        String value = raw.strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private String text(String value) { return value == null || value.isBlank() ? "未指定" : value.strip(); }

    private record RouteEnvelope(List<RouteDraft> routes) {}
    private record RouteDraft(String title, String origin, String opening, String preference, String startingRealm) {}
}
