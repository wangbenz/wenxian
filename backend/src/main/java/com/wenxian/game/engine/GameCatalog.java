package com.wenxian.game.engine;

import com.wenxian.game.model.ApiModels.Choice;
import com.wenxian.game.model.GameState;

import java.util.List;
import java.util.Map;

public final class GameCatalog {
    private GameCatalog() {}

    private static final Map<String, List<Choice>> CHOICES = Map.of(
            "alley", List.of(
                    new Choice("ask-credit", "请求赊药", "以三日为限，押上你的去路", "default"),
                    new Choice("trade-intel", "交换北山情报", "把你父亲留下的秘密当作筹码", "default"),
                    new Choice("leave", "离开后巷", "先回去确认妹妹的病情", "default")
            ),
            "bargain", List.of(
                    new Choice("accept-job", "接下试药差事", "今夜去城南义庄，替许百川取一只药匣", "default"),
                    new Choice("press-terms", "继续压价", "要求他先交出半株赤心草", "default"),
                    new Choice("walk-away", "拒绝交易", "保住自己，但妹妹只剩两天", "default")
            ),
            "mountain", List.of(
                    new Choice("follow-mark", "追查灰色印记", "它与父亲失踪那夜的车辙一致", "default"),
                    new Choice("sell-map", "只卖地图", "换药后不再过问北山", "default"),
                    new Choice("hide-clue", "收回情报", "许百川已经看出你有所隐瞒", "default")
            ),
            "home", List.of(
                    new Choice("use-medicine", "先用旧药压病", "能拖半日，但会耗尽家中药材", "default"),
                    new Choice("seek-doctor", "去请陈郎中", "需要十二文诊金", "default"),
                    new Choice("return-alley", "折返赌坊后巷", "许百川或许还未离开", "default")
            ),
            "shrine", List.of(
                    new Choice("open-box", "打开无名药匣", "匣内有活物般的敲击声", "danger"),
                    new Choice("take-token", "只取义庄木牌", "以证据换药，不碰匣中之物", "default"),
                    new Choice("retreat", "立刻退出义庄", "承认这不是凡人该碰的机缘", "default")
            )
    );

    public static List<Choice> choicesFor(String sceneId) {
        return CHOICES.getOrDefault(sceneId, CHOICES.get("alley"));
    }

    public static GameState initialState() {
        GameState state = new GameState();
        state.sceneId = "alley";
        state.minute = 14 * 60 + 20;
        state.day = 6;
        state.turn = 0;
        state.narrative = List.of(
                "许百川将半株赤心草收回药箱。",
                "远处的叫喊越来越近，赌坊的人正在搜你。",
                "妹妹的病情只能再拖两天。"
        );
        state.signals = List.of("商队已离镇", "赌坊警觉上升");
        state.player = new GameState.PlayerState("林砚", "凡人", "寒骨未测", 68, 23, 7, 36);
        state.characters = new java.util.ArrayList<>(List.of(
                new GameState.CharacterRecord("xu-baichuan", "许百川", "行脚药商", "戒备，准备离镇", "赌坊后巷", "可交易", 14 * 60 + 20),
                new GameState.CharacterRecord("lin-xiaohe", "林小禾", "妹妹", "寒症加重，最多再拖两天", "柳溪村西屋", "至亲", 13 * 60 + 40),
                new GameState.CharacterRecord("zhou-liu", "周六", "长胜赌坊管事", "正在搜人", "柳溪村东街", "敌视", 14 * 60 + 10)
        ));
        state.events = new java.util.ArrayList<>(List.of(
                new GameState.ChronicleEvent("event-0", "赤心草被收回", "许百川拒绝按原价卖药，妹妹的救命药再次落空。", 14 * 60 + 20, "risk"),
                new GameState.ChronicleEvent("event--1", "赌坊开始搜人", "你在赌局中看穿骰盅，长胜赌坊认定你出了千。", 13 * 60 + 55, "world")
        ));
        state.threads = new java.util.ArrayList<>(List.of(
                new GameState.QuestThread("medicine", "取得赤心草", "只剩两日", 72),
                new GameState.QuestThread("gambling", "摆脱赌坊追查", "搜捕范围扩大", 61),
                new GameState.QuestThread("mountain", "父亲与北山灰印", "线索未证实", 44)
        ));
        return state;
    }
}
