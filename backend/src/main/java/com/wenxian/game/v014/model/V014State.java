package com.wenxian.game.v014.model;

import java.util.List;

/**
 * V0.14 的轻状态命卷。它只保存需要跨回合保持一致的事实，不保存战力公式或传统 RPG 数值。
 */
public record V014State(
        int schemaVersion,
        String saveId,
        int turnNumber,
        String status,
        WorldProfile world,
        Protagonist protagonist,
        long worldTimeMinutes,
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
    public V014State {
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

        /** 兼容旧运行时的确定数量构造方式；旧状态在未显式标记前均视为数量已知。 */
        public Item(String id, String name, String type, String description,
                    String status, String source, int quantity, Boolean inPossession) {
            this(id, name, type, description, status, source, quantity, true, inPossession);
        }
    }

    public record Ability(String id, String name, String type, String description,
                          String status, String source) {}

    public record Character(String id, String name, String tier, String identity,
                            String relationship, String status, String lastKnownLocation, Boolean tracked) {
        public Character {
            if (tracked == null) {
                tracked = status == null || !"ARCHIVED".equalsIgnoreCase(status.strip());
            }
        }
    }

    public record Memory(String id, String title, String summary, long worldTimeMinutes,
                         String importance) {}

    private static boolean terminalItemNote(String value) {
        if (value == null || value.isBlank()) return false;
        String note = value.strip().toUpperCase();
        return List.of("CONSUMED", "SOLD", "GIFTED", "LOST", "REMOVED").contains(note)
                || note.matches("^(已)?(消耗|用完|卖出|出售|赠予|赠送|遗失|丢失|丢弃|不再持有|移出行囊)(完毕)?$");
    }
}
