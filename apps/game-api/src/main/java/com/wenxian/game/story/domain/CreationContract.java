package com.wenxian.game.story.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 玩家创建命卷时确认的只读创角事实。
 *
 * <p>契约记录“开局时是什么人、从哪里来、拥有什么”，普通回合只能推进当前状态，不能反向覆盖这些历史。
 * 开局实体由创建服务分配稳定 ID 后写入实体表，本对象只保存引用。</p>
 */
public record CreationContract(
        WorldBinding worldBinding,
        CharacterIdentity identity,
        CharacterOrigin origin,
        Opening opening,
        RealmBinding realmBinding
) {
    public CreationContract {
        worldBinding = required(worldBinding, "世界绑定");
        identity = required(identity, "角色身份");
        origin = required(origin, "角色根源");
        opening = required(opening, "开场处境");
        realmBinding = required(realmBinding, "境界绑定");
    }

    /** 创建时固定的世界包修订与展示快照。 */
    public record WorldBinding(
            String packageId,
            long revision,
            String nameSnapshot,
            String premiseSnapshot,
            String openingRegionRef
    ) {
        public WorldBinding {
            packageId = text(packageId, "世界包 ID");
            if (revision < 1) throw new IllegalArgumentException("世界包修订号必须大于零");
            nameSnapshot = text(nameSnapshot, "世界名称快照");
            premiseSnapshot = text(premiseSnapshot, "世界简介快照");
            openingRegionRef = text(openingRegionRef, "开场地域引用");
        }
    }

    /** 创建时确认的主角姓名、性别和世界内种族身份。 */
    public record CharacterIdentity(
            String protagonistName,
            String genderCode,
            String raceId,
            String raceNameSnapshot
    ) {
        public CharacterIdentity {
            protagonistName = text(protagonistName, "主角姓名");
            genderCode = text(genderCode, "性别编码");
            raceId = text(raceId, "种族 ID");
            raceNameSnapshot = text(raceNameSnapshot, "种族名称快照");
        }
    }

    /** 已确认的角色根源、未决问题与开局实体引用。 */
    public record CharacterOrigin(
            String summary,
            List<OriginFact> establishedFacts,
            List<StoryHook> openHooks,
            List<String> startingEntityRefs
    ) {
        public CharacterOrigin {
            summary = text(summary, "角色根源摘要");
            establishedFacts = copy(establishedFacts);
            openHooks = copy(openHooks);
            startingEntityRefs = copy(startingEntityRefs);
            unique(establishedFacts.stream().map(OriginFact::factId).toList(), "根源事实 ID");
            unique(openHooks.stream().map(StoryHook::hookId).toList(), "开放钩子 ID");
            unique(startingEntityRefs, "开局实体引用");
        }
    }

    /** 已经成立的过去事实，不表达尚未揭晓的答案。 */
    public record OriginFact(String factId, OriginFactType type, String statement) {
        public OriginFact {
            factId = text(factId, "根源事实 ID");
            type = required(type, "根源事实类型");
            statement = text(statement, "根源事实陈述");
        }
    }

    /** 玩家确认但尚未解决的开放问题，不是强制任务。 */
    public record StoryHook(String hookId, String summary) {
        public StoryHook {
            hookId = text(hookId, "开放钩子 ID");
            summary = text(summary, "开放钩子摘要");
        }
    }

    /** 创建时确认的开场位置与局势。 */
    public record Opening(String locationRef, String situation) {
        public Opening {
            locationRef = text(locationRef, "开场位置引用");
            situation = text(situation, "开场处境");
        }
    }

    /** 创建时由世界包固定的公共境界标尺与 L1 初期开局位置。 */
    public record RealmBinding(
            String scaleId,
            long scaleRevision,
            String startingRankKey,
            WorldPackage.RealmPhase startingPhase
    ) {
        public RealmBinding {
            scaleId = text(scaleId, "境界标尺 ID");
            if (scaleRevision < 1) throw new IllegalArgumentException("境界标尺修订号必须大于零");
            startingRankKey = text(startingRankKey, "开局境界等级键");
            if (!"L1".equals(startingRankKey)) throw new IllegalArgumentException("新角色必须从 L1 开始");
            startingPhase = required(startingPhase, "开局境界小阶段");
            if (startingPhase != WorldPackage.RealmPhase.EARLY) {
                throw new IllegalArgumentException("新角色必须从 L1 初期开始");
            }
        }
    }

    public enum OriginFactType {
        IDENTITY,
        PAST_EVENT,
        OBLIGATION,
        KNOWLEDGE
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        return value.strip();
    }

    private static <T> T required(T value, String field) {
        if (value == null) throw new IllegalArgumentException(field + "不能为空");
        return value;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void unique(List<String> values, String field) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (!seen.add(value)) throw new IllegalArgumentException(field + "重复：" + value);
        }
    }
}
