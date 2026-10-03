package com.wenxian.game.story.support;

import com.wenxian.game.story.domain.CreationContract;
import com.wenxian.game.story.domain.PlayPreference;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;

import java.time.Instant;
import java.util.List;

/** 新命卷测试使用的最小有效创角状态，避免测试绕过正式领域约束。 */
public final class StoryTestFixtures {
    private StoryTestFixtures() {}

    public static CreationContract creationContract() {
        return new CreationContract(
                new CreationContract.WorldBinding("XUANXU", 2, "玄墟界", "仙路真假难辨",
                        "region:xuanxu:qingqi"),
                new CreationContract.CharacterIdentity("测试角色", "UNSPECIFIED", "race:xuanxu:human", "人族"),
                new CreationContract.CharacterOrigin("测试根源",
                        List.of(new CreationContract.OriginFact("origin:fact:1",
                                CreationContract.OriginFactType.PAST_EVENT, "测试根源已经成立")),
                        List.of(), List.of()),
                new CreationContract.Opening("region:xuanxu:qingqi", "命卷初开"),
                new CreationContract.RealmBinding("realm-scale:test", 1, "L1",
                        com.wenxian.game.story.domain.WorldPackage.RealmPhase.EARLY));
    }

    public static PlayPreference playPreference() {
        return PlayPreference.defaults(Instant.parse("2026-08-22T00:00:00Z"));
    }

    public static CultivationSystem cultivationSystem() {
        List<CultivationSystem.Stage> stages = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new CultivationSystem.Stage("stage:test:" + index, index, "境界" + index,
                        "测试阶段" + index, List.of(), index == 6 ? List.of() : List.of("stage:test:" + (index + 1))))
                .toList();
        return new CultivationSystem("cultivation:test", 1, "测试修行", "测试修行体系", "人族",
                "stage:test:1", stages, new CultivationSystem.Confirmation(
                CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_CREATION,
                Instant.parse("2026-08-22T00:00:00Z")));
    }

    public static ProtagonistCultivation protagonistCultivation() {
        return new ProtagonistCultivation("cultivation:test", "stage:test:1", "");
    }

    public static ObjectNode state(ObjectMapper mapper, String saveId, int turnNumber,
                                   String location, String narrative) {
        ObjectNode state = mapper.createObjectNode();
        state.put("schemaVersion", 1);
        state.put("saveId", saveId);
        state.put("turnNumber", turnNumber);
        state.put("status", "ACTIVE");
        state.set("worldTime", mapper.valueToTree(StoryWorldTime.opening()));
        state.putObject("coreState").put("currentLocationRef", "region:xuanxu:qingqi")
                .put("currentLocationName", location).put("condition", "平静").put("lifeGoal", "问道");
        state.putObject("scene").put("title", "当前场景").put("narrative", narrative)
                .put("situation", location).putNull("pendingDecision").putArray("choices");
        return new StoryProjectionPersistenceMapper(mapper).writeInitialCreation(state, creationContract(),
                playPreference(), cultivationSystem(), protagonistCultivation());
    }
}
