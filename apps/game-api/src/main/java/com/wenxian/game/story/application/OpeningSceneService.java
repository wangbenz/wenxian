package com.wenxian.game.story.application;

import com.wenxian.game.story.domain.PlayPreference;
import com.wenxian.game.story.domain.WorldPackage;
import com.wenxian.game.story.model.StoryApiModels.OpeningSubmission;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 根据玩家已经确认的创角信息准备第零回合场景。
 *
 * <p>已预览开篇只做容量和结构核对，沿创建事务保存；不能从正文反向抽取角色事实。
 * 旧客户端未提交开篇时兼容原确定性组装，已提交但不完整的开篇必须拒绝，不静默替换。</p>
 */
@org.springframework.stereotype.Service
public class OpeningSceneService {
    public PreparedScene confirmed(OpeningSubmission opening) {
        var scene = opening.scene();
        if (scene == null || scene.choices() == null || scene.choices().isEmpty() || scene.choices().size() > 4) {
            throw new IllegalArgumentException("开场预览不完整，请重新准备");
        }
        return new PreparedScene(bounded(scene.title(), 80), bounded(scene.narrative(), 2400),
                bounded(opening.situation(), 300), scene.choices().stream().map(choice -> {
                    if (choice == null) throw new IllegalArgumentException("开场推荐行动不能为空");
                    return new ChoiceDraft(bounded(choice.title(), 80), bounded(choice.description(), 240));
                }).toList());
    }

    private String bounded(String value, int maximum) {
        if (value == null || value.isBlank() || value.strip().length() > maximum) {
            throw new IllegalArgumentException("开场内容缺失或超过容量，请重新准备");
        }
        return value.strip();
    }

    public PreparedScene prepare(WorldPackage world, WorldPackage.RaceDefinition race,
                                 WorldPackage.RegionDefinition region, String protagonistName,
                                 String originSummary, String situation, PlayPreference preference,
                                 List<String> startingEntityNames, List<String> openHooks) {
        String possessions = startingEntityNames.isEmpty() ? "" : " 你清楚自己此刻带着或牵系着："
                + String.join("、", startingEntityNames) + "。";
        String guidance = switch (preference.guidance()) {
            case LIGHT -> "眼前没有必须接受的道路。";
            case BALANCED -> "你可以先确认局势，也可以沿着自己的来处追查。";
            case EXPLICIT -> "当前可从环境、既有线索或自身状态中选择一个方向开始。";
        };
        String narrative = protagonistName + "，你以" + race.name() + "之身来到" + region.name() + "。"
                + sentence(originSummary) + sentence(situation) + possessions + " " + guidance;
        return new PreparedScene("命卷初开", narrative.strip(), situation.strip(), choices(preference, openHooks));
    }

    private String sentence(String value) {
        String clean = value.strip();
        if (clean.matches(".*[。！？!?]$")) return clean;
        return clean + "。";
    }

    private List<ChoiceDraft> choices(PlayPreference preference, List<String> openHooks) {
        LinkedHashSet<ChoiceDraft> result = new LinkedHashSet<>();
        for (PlayPreference.Focus focus : preference.focus()) {
            result.add(switch (focus) {
                case EXPLORATION -> new ChoiceDraft("观察当前环境", "确认地点、人物与眼前正在发生的事。");
                case RELATIONSHIP -> new ChoiceDraft("寻找可以交谈的人", "先从他人的立场和反应了解局势。");
                case CULTIVATION -> new ChoiceDraft("检视自身修行", "确认当前阶段、已有基础与可尝试的方向。");
                case MANAGEMENT -> new ChoiceDraft("盘点现有资源", "弄清自己能调用的物品、关系与落脚条件。");
                case COMBAT -> new ChoiceDraft("戒备潜在冲突", "观察威胁与退路，不主动替你发动战斗。");
            });
            if (result.size() == 2) break;
        }
        if (openHooks != null && !openHooks.isEmpty()) {
            result.add(new ChoiceDraft("追寻未解旧因", openHooks.getFirst()));
        }
        result.add(new ChoiceDraft("确认自身处境", "回想已经成立的来处，并确认当前能依靠什么。"));
        result.add(new ChoiceDraft("按自己的想法行动", "你也可以忽略推荐，直接输入想做的事。"));
        return new ArrayList<>(result).stream().limit(3).toList();
    }

    public record PreparedScene(String title, String narrative, String situation, List<ChoiceDraft> choices) {
        public PreparedScene {
            choices = List.copyOf(choices);
        }
    }

    public record ChoiceDraft(String title, String description) {}
}
