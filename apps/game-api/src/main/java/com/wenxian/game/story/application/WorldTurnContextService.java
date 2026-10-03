package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.CultivationSystem.ConfirmationSource;
import com.wenxian.game.story.worldpackage.application.WorldContextSelector;
import com.wenxian.game.story.worldpackage.application.WorldPackageQueryService;
import com.wenxian.game.story.worldpackage.domain.WorldContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 为已经校验命卷归属的回合准备流程读取精确修订并冻结公共世界资料。
 *
 * <p>本服务不查询创角默认项、不写数据库，也不把世界资料变成玩家已知事实。v2 缺少可证明的动态地域、
 * 包标签及故事线映射时明确不提供这些输入；不能利用可能滞留开场的地点引用猜测当前位置。
 * 修订缺失、封禁或快照损坏在模型调用前失败。此入口仅由服务端持有的命卷投影调用，不是包读取授权 API。</p>
 */
@Service
public class WorldTurnContextService {
    private final WorldPackageQueryService registry;
    private final WorldContextSelector selector;
    private final String legacyNamespace;

    public WorldTurnContextService(WorldPackageQueryService registry, ObjectMapper mapper,
                                  @Value("${game.world-packages.legacy-namespace:wenxian.official}") String legacyNamespace) {
        this.registry = registry;
        this.selector = new WorldContextSelector(mapper);
        this.legacyNamespace = legacyNamespace;
    }

    public WorldContext select(StoryProjection projection, String playerAction) {
        if (projection == null || projection.creationContract() == null) {
            throw new IllegalArgumentException("世界资料选择缺少命卷创角事实");
        }
        var contract = projection.creationContract();
        var binding = contract.worldBinding();
        var resolved = registry.portable(legacyNamespace, binding.packageId(), binding.revision());
        List<String> unavailable = new ArrayList<>(List.of(
                "CURRENT_REGION_UNVERIFIED_V2", "ENTITY_TAG_MAPPING_UNAVAILABLE_V2", "STORY_THREAD_MAPPING_UNAVAILABLE_V2"));
        String stageId = null;
        var savedSystem = projection.cultivationSystem();
        var packagedSystem = savedSystem == null ? null
                : resolved.compiled().progressionSystemsById().get(savedSystem.systemId());
        var realmBinding = contract.realmBinding();
        if (packagedSystem != null && projection.protagonistCultivation() != null
                && savedSystem.confirmation() != null
                && savedSystem.confirmation().source() == ConfirmationSource.WORLD_PACKAGE_FIXED
                && savedSystem.schemaVersion() == packagedSystem.revision()
                && realmBinding.scaleId().equals(packagedSystem.systemId())
                && realmBinding.scaleRevision() == packagedSystem.revision()
                && savedSystem.systemId().equals(projection.protagonistCultivation().systemId())) {
            stageId = projection.protagonistCultivation().currentStageId();
        } else {
            unavailable.add("PROGRESSION_SYSTEM_NOT_BOUND_TO_PACKAGE");
        }
        return selector.select(resolved, new WorldContextSelector.Query(contract.identity().raceId(), null,
                stageId, Set.of(), Set.of(), playerAction, unavailable));
    }
}
