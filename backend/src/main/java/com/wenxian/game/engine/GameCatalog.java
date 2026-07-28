package com.wenxian.game.engine;

import com.wenxian.game.model.ApiModels.BootstrapResponse;
import com.wenxian.game.model.ApiModels.GoalView;
import com.wenxian.game.model.ApiModels.DifficultyView;
import com.wenxian.game.model.ApiModels.RaceView;
import com.wenxian.game.model.GameState;

import java.util.ArrayList;
import java.util.List;

public final class GameCatalog {
    private GameCatalog() {}

    public static BootstrapResponse bootstrap() {
        return new BootstrapResponse(
                "问仙录", "V0.11", fixedWorld(),
                List.of(
                        new RaceView("HUMAN", "人族", "寿元短暂，却拥有最完整的宗门、丹道与传承体系。",
                                List.of("传承选择丰富", "擅长组织与交易"), List.of("寿元最短", "凡俗身份束缚深"), "凡俗约六十至九十年"),
                        new RaceView("DEMON", "妖族", "依靠血脉与漫长岁月成长，肉身强横，却常被人族视作材料与祸患。",
                                List.of("肉身强横", "血脉可觉醒"), List.of("传承稀缺", "容易遭到猎杀"), "通常高于同境人族"),
                        new RaceView("SPIRIT", "灵族", "由山川、草木或器物孕育，亲近天地规则，却难以离开本源之地。",
                                List.of("规则感知敏锐", "天生灵性"), List.of("本源受限", "容易被炼化利用"), "因本源差异而异")
                ),
                List.of(
                        new GoalView("LONGEVITY", "求长生", "不惜代价验证仙路是否真实存在。"),
                        new GoalView("KINSHIP", "护亲友", "修行首先是为了守住不愿失去的人。"),
                        new GoalView("POWER", "求力量", "不再任由宗门、家族或强者决定命运。"),
                        new GoalView("TRUTH", "探仙路", "追查绝天之变与飞升断绝的真相。"),
                        new GoalView("FREEDOM", "求自在", "不愿被血脉、契约或宗门定义一生。")
                ),
                List.of(
                        new DifficultyView("NORMAL", "普通", "更适合休闲阅读。世界仍有代价，但会给你更多周旋与补救的余地。"),
                        new DifficultyView("HARD", "困难", "资源更紧、失误更重，人物不会为主角让路。适合想体验残酷仙途的人。")
                ),
                List.of("凡俗", "引气", "筑基", "金丹", "元婴", "化神", "炼虚", "合道")
        );
    }

    public static GameState.WorldProfile fixedWorld() {
        GameState.WorldProfile world = new GameState.WorldProfile();
        world.id = "xuanyu-world";
        world.title = "玄墟界";
        world.era = "绝天之变后三千年";
        world.summary = "三千年前，最后一批飞升者在天门前失踪。此后再无人能证明仙界存在，宗门却仍以长生之名争夺灵脉、血脉与凡人性命。问仙录记录的不是天命之子，而是每一个曾向仙路发问的人。";
        world.cultivationLaw = "修行需要资质、资源、传承与时间。境界提供下限，功法、心境、情报和克制关系决定生死。任何力量都必须有来源与代价。";
        world.dangerRule = "世界不会等待主角。人物会衰老、突破、背叛和死亡；错失的机缘不会原地停留，死亡也不会自动回档。";
        world.regions = List.of("东陆九州", "北荒妖原", "南溟群岛", "西境灵墟");
        world.factions = List.of("九宗盟", "百妖庭", "灵墟古族", "问天司");
        world.generatedBy = "fixed-canon:v0.1";
        return world;
    }

    public static GameState start(GameState setup, String protagonistId) {
        GameState.ProtagonistOption option = setup.protagonistOptions.stream()
                .filter(candidate -> candidate.id.equals(protagonistId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知主角命格"));
        GameState.OpeningSeed opening = option.opening;
        GameState state = new GameState();
        state.saveId = setup.saveId;
        state.difficulty = setup.difficulty;
        state.phase = "talent";
        state.world = fixedWorld();
        state.worldYear = 3001;
        state.protagonistOptions = new ArrayList<>();
        state.sceneId = opening.sceneId;
        state.sceneTitle = opening.sceneTitle;
        state.currentLocation = opening.location;
        state.day = opening.day;
        state.minute = opening.minute;
        state.narrative = new ArrayList<>(opening.narrative);
        state.signals = new ArrayList<>(opening.signals);
        state.choices = new ArrayList<>(opening.choices);
        state.player = new GameState.PlayerState(option.name, option.race, option.gender, option.age,
                lifespanFor(option.race), option.identity, option.lifeGoal, option.goal, option.burden,
                "凡俗", option.constitution, option.vitality, option.silver, option.insight, option.suspicion);
        state.characters = new ArrayList<>(opening.characters);
        state.threads = new ArrayList<>(opening.threads);
        state.inventory = initialInventory(option);
        state.abilities = initialAbilities(option);
        state.talentOptions = new ArrayList<>();
        state.talentRerolled = false;
        state.events = new ArrayList<>(List.of(new GameState.ChronicleEvent(
                "event-origin", "命卷开启 · " + option.name,
                option.identity + "选择在" + opening.location + "面对自己的第一场因果。",
                3001 * 360 * 1440 + Math.max(0, opening.day - 1) * 1440 + opening.minute, "world"
        )));
        return state;
    }

    public static GameState enterWorld(GameState selected, String talentId) {
        if (!"talent".equals(selected.phase)) throw new IllegalArgumentException("当前命卷不在天赋选择阶段");
        GameState.AbilityRecord talent = selected.talentOptions.stream()
                .filter(option -> option.id.equals(talentId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("未知天赋"));
        selected.abilities.add(talent);
        selected.talentOptions = new ArrayList<>();
        selected.phase = "playing";
        selected.events.add(0, new GameState.ChronicleEvent(
                "event-awakening", "天赋初醒 · " + talent.name,
                "入世之初，" + selected.player.name + "选择承认自身的异处：" + talent.description,
                selected.worldYear * 360 * 1440 + Math.max(0, selected.day - 1) * 1440 + selected.minute, "gain"
        ));
        return selected;
    }

    public static GameState enterWorldWithoutTalent(GameState selected) {
        if (!"talent".equals(selected.phase)) throw new IllegalArgumentException("当前命卷不在天赋选择阶段");
        selected.talentOptions = new ArrayList<>();
        selected.phase = "playing";
        selected.events.add(0, new GameState.ChronicleEvent(
                "event-no-awakening", "凡骨入世",
                selected.player.name + "没有强求额外天赋，只凭命格与选择踏入此世。",
                selected.worldYear * 360 * 1440 + Math.max(0, selected.day - 1) * 1440 + selected.minute, "decision"
        ));
        return selected;
    }

    private static int lifespanFor(String race) {
        return switch (race) {
            case "DEMON" -> 160;
            case "SPIRIT" -> 240;
            default -> 75;
        };
    }

    private static ArrayList<GameState.InventoryItem> initialInventory(GameState.ProtagonistOption option) {
        ArrayList<GameState.InventoryItem> result = new ArrayList<>();
        result.add(new GameState.InventoryItem("item-origin-token", "origin-token", "身世旧物", "KEY_ITEM",
                "凡物", 1, "与这具身份的过去相连，价值尚未显现。", true));
        if (option.silver > 0) {
            result.add(new GameState.InventoryItem("item-copper", "copper-coin", "铜钱", "CURRENCY",
                    "凡物", option.silver, "凡俗城镇通用的钱币。", false));
        }
        return result;
    }

    private static ArrayList<GameState.AbilityRecord> initialAbilities(GameState.ProtagonistOption option) {
        ArrayList<GameState.AbilityRecord> result = new ArrayList<>();
        result.add(new GameState.AbilityRecord("ability-origin-trait", "origin-trait", option.trait, "TALENT",
                "初显", 1, "命格", "这不是无条件的优势，它会在合适的处境中产生作用。"));
        return result;
    }
}
