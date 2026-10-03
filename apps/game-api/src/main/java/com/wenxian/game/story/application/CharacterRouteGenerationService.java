package com.wenxian.game.story.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.credit.PublicTurnLimitException;
import com.wenxian.game.story.agent.ai.CharacterCreationAi.CharacterRouteDraft;
import com.wenxian.game.story.agent.ai.CharacterCreationAi.RouteDraft;
import com.wenxian.game.story.agent.ai.CharacterCreationAi.StartingEntityDraft;
import com.wenxian.game.story.agent.ai.CharacterCreationAi.OpeningSceneDraft;
import com.wenxian.game.story.agent.ai.CharacterCreationAi.OpeningChoiceDraft;
import com.wenxian.game.story.agent.ai.CharacterRouteGenerator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 生成三份推荐路线，或根据最终来历准备一份角色与开场草案。
 *
 * <p>模型只负责语义候选；稳定 ID、字段长度和候选数量由服务端确定。本用例不创建命卷、不写故事事实。</p>
 */
@Service
public class CharacterRouteGenerationService {
    private final CharacterRouteGenerator generator;
    private final ObjectMapper mapper;

    public CharacterRouteGenerationService(CharacterRouteGenerator generator, ObjectMapper mapper) {
        this.generator = generator;
        this.mapper = mapper;
    }

    public List<CharacterRouteCandidate> generate(String userId, Input input) {
        try {
            CharacterRouteDraft draft = generator.generate(userId,
                    "character-route-" + UUID.randomUUID(), mapper.writeValueAsString(input));
            return normalize(draft, input.mode());
        } catch (GameApiException error) {
            throw error;
        } catch (PublicTurnLimitException error) {
            throw error;
        } catch (JsonProcessingException error) {
            throw failure("角色创建上下文无法编码", error);
        } catch (RuntimeException error) {
            throw failure("角色路线模型没有返回可用结构", error);
        }
    }

    List<CharacterRouteCandidate> normalize(CharacterRouteDraft draft, String mode) {
        int expected = "FINALIZE".equals(mode) ? 1 : 3;
        if (draft == null || draft.routes() == null || draft.routes().size() != expected) {
            throw new IllegalStateException("模型返回的角色候选数量不符合本次请求");
        }
        List<CharacterRouteCandidate> routes = new ArrayList<>();
        for (int index = 0; index < draft.routes().size(); index++) {
            RouteDraft route = draft.routes().get(index);
            if (route == null) throw new IllegalStateException("角色路线字段不完整");
            routes.add(new CharacterRouteCandidate("route-" + (index + 1), required(route.title(), 12),
                    required(route.origin(), 240), required(route.opening(), 300),
                    facts(route.establishedFacts()), hooks(route.openHooks()), entities(route.startingEntities()),
                    scene(route.scene())));
        }
        return List.copyOf(routes);
    }

    private OpeningSceneDraft scene(OpeningSceneDraft value) {
        if (value == null || value.choices() == null || value.choices().isEmpty() || value.choices().size() > 4) {
            throw new IllegalStateException("开场正文与推荐行动不完整");
        }
        return new OpeningSceneDraft(bounded(value.title(), 80), bounded(value.narrative(), 2400),
                value.choices().stream().map(choice -> {
                    if (choice == null) throw new IllegalStateException("开场推荐行动不完整");
                    return new OpeningChoiceDraft(bounded(choice.title(), 80), bounded(choice.description(), 240));
                }).toList());
    }

    private String bounded(String value, int maximum) {
        if (value == null || value.isBlank() || value.strip().length() > maximum) {
            throw new IllegalStateException("开场字段缺失或超过容量");
        }
        return value.strip();
    }

    private String required(String value, int maximum) {
        if (value == null || value.isBlank()) throw new IllegalStateException("角色路线字段不完整");
        return truncate(value, maximum);
    }

    private List<String> facts(List<String> values) {
        List<String> result = clean(values, 6, 240);
        if (result.isEmpty()) throw new IllegalStateException("角色路线必须包含至少一条已成立事实");
        return result;
    }

    private List<String> hooks(List<String> values) {
        return clean(values, 6, 200);
    }

    private List<String> clean(List<String> values, int maximumCount, int maximumLength) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> truncate(value, maximumLength)).distinct().limit(maximumCount).toList();
    }

    private List<StartingEntityCandidate> entities(List<StartingEntityDraft> values) {
        if (values == null) return List.of();
        List<StartingEntityCandidate> result = new ArrayList<>();
        for (StartingEntityDraft value : values) {
            if (value == null) continue;
            String type = required(value.entityType(), 20).toUpperCase(java.util.Locale.ROOT);
            if (!List.of("ITEM", "ABILITY", "CHARACTER").contains(type)) {
                throw new IllegalStateException("角色路线包含不支持的开局实体类型");
            }
            result.add(new StartingEntityCandidate("origin:entity:" + (result.size() + 1), type,
                    required(value.name(), 80), required(value.summary(), 300)));
            if (result.size() == 6) break;
        }
        return List.copyOf(result);
    }

    private String truncate(String value, int maximum) {
        String clean = value.strip();
        return clean.substring(0, Math.min(clean.length(), maximum));
    }

    private GameApiException failure(String detail, Throwable error) {
        return GameApiException.temporary("CHARACTER_ROUTE_GENERATION_FAILED", "角色路线未能完成",
                "本次推演没有生成可用的角色路线，不会创建存档或消耗游戏回合。",
                List.of("重新推演", "直接填写角色设定"), detail, error);
    }

    /** 角色路线生成所需的中性上下文。 */
    public record Input(String worldPackageId, long worldRevision, String worldName, String worldPremise,
                        List<String> worldRules, String narrativeGuidance, String openingRegionRef,
                        String openingRegionName, String playerName, String raceId, String raceName, String genderCode,
                        String roleSeed, String preferenceSeed, String mode, String startingRealmName) {
        public Input {
            mode = mode == null ? "RECOMMEND" : mode;
            if (!List.of("RECOMMEND", "FINALIZE").contains(mode)) throw new IllegalArgumentException("未知创角模式");
            if ("FINALIZE".equals(mode) && (roleSeed == null || roleSeed.isBlank())) {
                throw new IllegalArgumentException("准备开场需要玩家最终来历");
            }
        }

        /** 兼容已有生成调用；正式入口另传当前世界包的开局境界。 */
        public Input(String worldPackageId, long worldRevision, String worldName, String worldPremise,
                     List<String> worldRules, String narrativeGuidance, String openingRegionRef,
                     String openingRegionName, String playerName, String raceId, String raceName, String genderCode,
                     String roleSeed, String preferenceSeed) {
            this(worldPackageId, worldRevision, worldName, worldPremise, worldRules, narrativeGuidance,
                    openingRegionRef, openingRegionName, playerName, raceId, raceName, genderCode,
                    roleSeed, preferenceSeed, "RECOMMEND", null);
        }
    }

    /** 进入 HTTP 适配层前的稳定候选。 */
    public record CharacterRouteCandidate(String id, String title, String origin, String opening,
                                          List<String> establishedFacts, List<String> openHooks,
                                          List<StartingEntityCandidate> startingEntities, OpeningSceneDraft scene) {}

    public record StartingEntityCandidate(String localRef, String entityType, String name, String summary) {}
}
