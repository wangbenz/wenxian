package com.wenxian.v2.game.application;

import com.wenxian.v2.game.persistence.DomainEffectRepository;
import com.wenxian.v2.game.persistence.DomainEffectRepository.DueEvent;
import com.wenxian.v2.game.persistence.DomainEffectRepository.DueSchedule;
import com.wenxian.v2.game.persistence.TurnRepository.DomainChange;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AutonomousWorldService {
    private static final List<String> STAGES = List.of(
            "SEED", "SIGN", "BUILDUP", "ERUPTION", "TURN", "RESULT", "AFTERMATH");
    private final DomainEffectRepository repository;

    public AutonomousWorldService(DomainEffectRepository repository) {
        this.repository = repository;
    }

    public List<DomainChange> advanceDueEvents(String turnId, String saveId, long worldMinute,
                                               Instant now, int firstOrder) {
        List<DomainChange> changes = new ArrayList<>();
        int order = firstOrder;
        for (int pass = 0; pass < 8; pass++) {
            List<DueEvent> due = repository.dueEvents(saveId, worldMinute);
            if (due.isEmpty()) break;
            for (DueEvent event : due) {
                String next = nextStage(event.stage());
                if (next == null) continue;
                long transitionMinute = event.nextTransitionWorldMinute();
                String summary = summary(event, next);
                repository.advanceDueEvent(saveId, event, next, summary, transitionMinute, now);
                changes.add(new DomainChange("v2-change-" + UUID.randomUUID(), turnId, saveId, order++,
                        "EVENT", event.id(), "EVENT_TIME_ADVANCED", event.stage(), next,
                        event.title() + "没有等待玩家，已随世界时间自行发展：" + summary, now));
            }
        }
        return List.copyOf(changes);
    }

    public List<DomainChange> advanceDueSchedules(String turnId, String saveId, long worldMinute,
                                                  Instant now, int firstOrder) {
        List<DomainChange> changes = new ArrayList<>();
        int order = firstOrder;
        for (int pass = 0; pass < 8; pass++) {
            List<DueSchedule> due = repository.dueSchedules(saveId, worldMinute);
            if (due.isEmpty()) break;
            for (DueSchedule schedule : due) {
                var resolution = repository.resolveNpcSchedule(saveId, schedule, now);
                changes.add(new DomainChange("v2-change-" + UUID.randomUUID(), turnId, saveId, order++,
                        "CHARACTER", schedule.characterId(), "NPC_GOAL_ADVANCED",
                        schedule.currentGoal(), resolution.currentGoal(), resolution.summary(), now));
            }
        }
        return List.copyOf(changes);
    }

    private String nextStage(String current) {
        int index = STAGES.indexOf(current);
        return index < 0 || index + 1 >= STAGES.size() ? null : STAGES.get(index + 1);
    }

    private String summary(DueEvent event, String stage) {
        return switch (event.templateCode()) {
            case "QINGQI_MINE_DISTURBANCE" -> switch (stage) {
                case "SIGN" -> "照骨矿深处的闷响变得频繁，已有矿工拒绝进入旧坑道。";
                case "BUILDUP" -> "青铜门与矿主分别派人封锁消息，青木镇的矿价开始波动。";
                case "ERUPTION" -> "照骨矿旧坑塌陷，灰白矿尘涌出，通往矿区的道路暂时受阻。";
                case "TURN" -> "矿区幸存者与青铜门发生争执，一份关于旧矿脉的证言流入市井。";
                case "RESULT" -> "照骨矿被重新划分管辖，部分旧坑永久封闭。";
                default -> "矿乱已经过去，废坑、失踪者与改变后的矿价成为青岐州新的日常。";
            };
            case "QINGQI_MEDICINE_SHORTAGE" -> switch (stage) {
                case "SIGN" -> "回春堂提高了几味常用药的收购价，百草岭的采药人明显增多。";
                case "BUILDUP" -> "药商开始囤货，镇中病患购买药汤比往日更难。";
                case "ERUPTION" -> "一批误采毒草混入货栈，回春堂暂时停收散户药材。";
                case "TURN" -> "药农与回春堂重新约定验药办法，外来药商也开始入场。";
                case "RESULT" -> "常用药供给逐渐恢复，但几味稀缺药草仍维持高价。";
                default -> "药荒退去，新的验药与收购规矩留在青木镇。";
            };
            case "QINGQI_CARAVAN_DELAY" -> switch (stage) {
                case "SIGN" -> "北路商队继续误期，驿道沿线开始出现零散弃货。";
                case "BUILDUP" -> "青木集市的北货减少，护送与探路的报酬随之上涨。";
                case "ERUPTION" -> "山兽迁徙截断一段驿道，数支商队被迫在旧驿站合流。";
                case "TURN" -> "猎户与商队共同开出临时绕行路，盗匪也开始趁乱活动。";
                case "RESULT" -> "北路恢复有限通行，新的绕行路成为商旅备用路线。";
                default -> "迟滞结束后，北境货价与护路规矩已经不同于从前。";
            };
            case "BRONZE_GATE_RECRUITMENT" -> switch (stage) {
                case "SIGN" -> "青铜门外院张出招募条件，杂役、客工与记名学徒各有不同门槛。";
                case "BUILDUP" -> "求学者与中人聚集外院，围绕名额的交易和欺骗一同增加。";
                case "ERUPTION" -> "外院公开考核开始，一批伪造来历者被逐出山门。";
                case "TURN" -> "青铜门临时调整名额，更看重矿务、药理与护路经验。";
                case "RESULT" -> "本轮招募结束，新入门者被分往不同差事。";
                default -> "招募榜撤下，未被选中者散入青岐州各处，等待下一次机会。";
            };
            default -> switch (stage) {
                case "SIGN" -> "原本隐约的征兆开始被更多人察觉。";
                case "BUILDUP" -> "各方围绕此事的行动逐渐交织。";
                case "ERUPTION" -> "积累已久的矛盾终于公开爆发。";
                case "TURN" -> "事态在多方选择下出现转折。";
                case "RESULT" -> "主要结果已经形成。";
                default -> "事情已经结束，但留下的影响仍在延续。";
            };
        };
    }
}
