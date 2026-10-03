package com.wenxian.game.story.domain;

import java.util.List;

/**
 * 中立故事运行时的轻量命卷快照。
 *
 * <p>该对象只承载 H5 当前需要展示的投影和跨回合稳定字段，不是模型输出，也不是全部权威事实。
 * 物品、能力、人物和记忆的权威记录仍分别来自 story_entity 与 story_memory；引擎读取命卷时再组装为本视图。
 * 破坏性新版本只读取语义世界时钟，不再兼容分钟级命卷快照。</p>
 */
public record StoryRuntimeState(
        int schemaVersion,
        String saveId,
        int turnNumber,
        String status,
        WorldProfile world,
        Protagonist protagonist,
        StoryWorldTime worldTime,
        String currentLocation,
        String condition,
        String sceneTitle,
        String narrative,
        List<Choice> choices,
        List<Item> inventory,
        List<Ability> abilities,
        List<Character> characters,
        List<Memory> memories
) {
    public StoryRuntimeState {
        if (worldTime == null) throw new IllegalArgumentException("命卷缺少语义世界时间");
        choices = safe(choices);
        inventory = safe(inventory);
        abilities = safe(abilities);
        characters = safe(characters);
        memories = safe(memories);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record WorldProfile(String code, String name, String source, String premise,
                               String cultivationStyle, String openingRegion) {}

    public record Protagonist(String name, String race, String gender, String origin,
                              String realm, String lifeGoal, String storyTone) {}

    public record Choice(String id, String title, String description) {}

    public record Item(String id, String name, String type, String description,
                       String status, String source, int quantity, Boolean quantityKnown,
                       Boolean inPossession) {
        public Item {
            if (quantityKnown == null) quantityKnown = true;
            if (inPossession == null) {
                inPossession = (!quantityKnown || quantity > 0) && !terminalItemNote(status);
            }
        }

        /** 兼容尚未显式区分数量可信度的历史轻状态。 */
        public Item(String id, String name, String type, String description,
                    String status, String source, int quantity, Boolean inPossession) {
            this(id, name, type, description, status, source, quantity, true, inPossession);
        }
    }

    /** 能力展示保留旧状态文案，并独立携带可信学习状态；缺字段的旧快照不推断掌握程度。 */
    public record Ability(String id, String name, String type, String description,
                          String status, String source, StoryPatch.AbilityState abilityState) {
        public Ability(String id, String name, String type, String description,
                       String status, String source) {
            this(id, name, type, description, status, source, null);
        }
    }

    public record Character(String id, String name, String tier, String identity,
                            String relationship, String status, String lastKnownLocation, Boolean tracked) {
        public Character {
            if (tracked == null) tracked = status == null || !"ARCHIVED".equalsIgnoreCase(status.strip());
        }
    }

    public record Memory(String id, String title, String summary, StoryWorldTime worldTime,
                         String importance) {}

    private static boolean terminalItemNote(String value) {
        if (value == null || value.isBlank()) return false;
        String note = value.strip().toUpperCase();
        return List.of("CONSUMED", "SOLD", "GIFTED", "LOST", "REMOVED").contains(note)
                || note.matches("^(已)?(消耗|用完|卖出|出售|赠予|赠送|遗失|丢失|丢弃|不再持有|移出行囊)(完毕)?$");
    }
}
