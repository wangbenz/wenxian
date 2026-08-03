package com.wenxian.v2.game.domain;

import java.util.List;

public final class RealmLadder {
    private static final List<Realm> HUMAN = List.of(
            realm("MORTAL", "凡俗"), realm("QI_DRAWING", "引气"), realm("FOUNDATION", "筑基"),
            realm("GOLDEN_CORE", "金丹"), realm("NASCENT_SOUL", "元婴"), realm("SPIRIT_TRANSFORMATION", "化神"),
            realm("VOID_REFINING", "炼虚"), realm("DAO_UNION", "合道"));
    private static final List<Realm> DEMON = List.of(
            realm("AWAKENED", "开智"), realm("SPIRIT_AWAKENING", "启灵"), realm("BLOOD_TEMPERING", "淬血"),
            realm("DEMON_CORE", "妖丹"), realm("TRUE_FORM", "真形"), realm("ANCESTRAL_RETURN", "返祖"),
            realm("HEAVENLY_DEMON", "天妖"), realm("DEMON_SAINT", "妖圣"));
    private static final List<Realm> SPIRIT = List.of(
            realm("SPROUTING_SPIRIT", "萌灵"), realm("GATHERED_MIND", "聚识"), realm("STABLE_FORM", "定形"),
            realm("SPIRIT_CORE", "灵核"), realm("TRUE_NAME", "真名"), realm("MANIFEST_SAINT", "显圣"),
            realm("DOMAIN_FORMATION", "化域"), realm("HEAVENLY_COMPLETION", "天成"));

    private RealmLadder() {
    }

    public static Realm initial(Race race) {
        return ladder(race).getFirst();
    }

    public static boolean isAdjacent(Race race, String currentCode, String targetCode) {
        List<Realm> realms = ladder(race);
        int current = indexOf(realms, currentCode);
        int target = indexOf(realms, targetCode);
        return current >= 0 && target == current + 1;
    }

    public static Realm byCode(Race race, String code) {
        return ladder(race).stream()
                .filter(realm -> realm.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("境界不属于当前种族"));
    }

    public static Realm next(Race race, String currentCode) {
        List<Realm> realms = ladder(race);
        int current = indexOf(realms, currentCode);
        return current >= 0 && current + 1 < realms.size() ? realms.get(current + 1) : null;
    }

    public static int rank(Race race, String code) {
        return indexOf(ladder(race), code);
    }

    private static int indexOf(List<Realm> realms, String code) {
        for (int i = 0; i < realms.size(); i++) {
            if (realms.get(i).code().equals(code)) return i;
        }
        return -1;
    }

    private static List<Realm> ladder(Race race) {
        return switch (race) {
            case HUMAN -> HUMAN;
            case DEMON -> DEMON;
            case SPIRIT -> SPIRIT;
        };
    }

    private static Realm realm(String code, String displayName) {
        return new Realm(code, displayName);
    }

    public record Realm(String code, String displayName) {
    }
}
