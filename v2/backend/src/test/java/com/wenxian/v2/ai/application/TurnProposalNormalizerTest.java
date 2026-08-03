package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.ai.domain.IntentContext.EntityCandidate;
import com.wenxian.v2.ai.domain.IntentContext.ProtagonistFacts;
import com.wenxian.v2.ai.domain.TurnNarrativeContext;
import com.wenxian.v2.ai.domain.TurnProposal;
import com.wenxian.v2.ai.domain.TurnProposal.DomainEffectProposal;
import com.wenxian.v2.ai.domain.TurnProposal.ConfirmedFactProposal;
import com.wenxian.v2.ai.domain.TurnProposal.Generated;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TurnProposalNormalizerTest {
    private final TurnProposalNormalizer normalizer = new TurnProposalNormalizer();

    @Test
    void removesInventedIncidentalAbilityButKeepsStrictPrimaryAbilityReference() {
        Generated investigate = normalizer.normalize(context(), generated(intent("INVESTIGATE", "invented-power"),
                List.of(new DomainEffectProposal("ABILITY_USED", "invented-power", null,
                        null, null, null, "模型误把观察写成能力"))));
        assertThat(investigate.proposal().intent().referencedAbilityIds()).isEmpty();
        assertThat(investigate.proposal().domainEffects()).isEmpty();

        Generated useAbility = normalizer.normalize(context(), generated(intent("USE_ABILITY", "invented-power"),
                List.of()));
        assertThat(useAbility.proposal().intent().referencedAbilityIds()).containsExactly("invented-power");
    }

    @Test
    void canonicalizesNamesAndRepairsOnlyLowRiskStructuredFields() {
        ActionIntent intent = new ActionIntent("CULTIVATE", "ATTEMPT", null, null, null,
                List.of(), List.of("ability-definition-1"), null, false, List.of(), "调息", 3L, "YEAR");
        Generated normalized = normalizer.normalize(context(), generated(intent, List.of(
                new DomainEffectProposal("ABILITY_USED", "模型写错的能力实体", null, null,
                        null, null, "循着石中回响调息"),
                new DomainEffectProposal("CULTIVATION_INSIGHT", "模型写错的主角实体", null, null,
                        null, "", "懂得循着石纹缓慢纳息"),
                new DomainEffectProposal("EVENT_ADVANCED", "矿难", null, null,
                        BigDecimal.ONE, "RESULT", "矿道中的异声更加密集"))));

        assertThat(normalized.proposal().intent().referencedAbilityIds()).containsExactly("ability-1");
        assertThat(normalized.proposal().domainEffects().getFirst().entityId()).isEqualTo("ability-1");
        assertThat(normalized.proposal().domainEffects().get(1).value()).isEqualTo("懂得循着石纹缓慢纳息");
        assertThat(normalized.proposal().domainEffects()).hasSize(2);

        Generated investigate = normalizer.normalize(context(), generated(intent("INVESTIGATE", ""), List.of(
                new DomainEffectProposal("EVENT_ADVANCED", "矿难", null, null,
                        BigDecimal.ONE, "RESULT", "矿道中的异声更加密集"))));
        assertThat(investigate.proposal().domainEffects().getFirst().entityId()).isEqualTo("event-1");
        assertThat(investigate.proposal().domainEffects().getFirst().value()).isEqualTo("SIGN");
    }

    @Test
    void canonicalizesDurableFactsAndDropsUnknownEntities() {
        TurnProposal proposal = new TurnProposal(intent("INVESTIGATE", ""), 60L,
                "INFORMATION_DISCOVERED", "听见异响", "你从石壁后听见了矿道深处反复出现的凿击声。",
                List.of(
                        new ConfirmedFactProposal("LOCATION", "照骨矿", "矿道深处每到夜间便会出现三次凿击声"),
                        new ConfirmedFactProposal("KNOWLEDGE", "某段传闻", "旧矿役曾隐瞒过一次坍塌"),
                        new ConfirmedFactProposal("CHARACTER", "不存在的人", "一个未登场的人声称认识你")),
                List.of(), List.of());
        Generated normalized = normalizer.normalize(context(), new Generated(proposal, "DEEPSEEK", "flash"));

        assertThat(normalized.proposal().confirmedFacts()).containsExactly(
                new ConfirmedFactProposal("LOCATION", "mine", "矿道深处每到夜间便会出现三次凿击声"),
                new ConfirmedFactProposal("KNOWLEDGE", "save", "旧矿役曾隐瞒过一次坍塌"));
    }

    @Test
    void dropsCultivationInsightFromAnUnrelatedAbilityUse() {
        Generated normalized = normalizer.normalize(context(), generated(intent("USE_ABILITY", "ability-1"),
                List.of(new DomainEffectProposal("CULTIVATION_INSIGHT", "hero", null, null,
                        null, "听到了旧日声音", "使用天赋得到普通线索"))));

        assertThat(normalized.proposal().domainEffects()).isEmpty();
    }

    @Test
    void resolvesAnExplicitNamedWaitPeriodFromTheAuthoritativeWorldClock() {
        IntentContext base = context().authority();
        long morning = com.wenxian.v2.game.domain.WorldCalendar.toWorldMinute(3001, 1, 2, 9 * 60 + 30);
        IntentContext waiting = new IntentContext(base.userId(), base.saveId(), base.operationId(),
                base.expectedStateVersion(), "等待到午后再看谁来取货", base.protagonist(),
                base.currentLocationId(), base.currentLocationName(), morning, base.characterCandidates(),
                base.locationCandidates(), base.itemCandidates(), base.abilityCandidates(),
                base.itemDefinitionCandidates(), base.walletCandidates(), base.abilityDefinitionCandidates());
        TurnNarrativeContext waitingContext = new TurnNarrativeContext("玄墟界", "NORMAL", waiting,
                List.of(), List.of(), List.of());
        ActionIntent waitIntent = new ActionIntent("WAIT", "ATTEMPT", null, null, null,
                List.of(), List.of(), null, false, List.of(), "等到午后");

        Generated normalized = normalizer.normalize(waitingContext, generated(waitIntent, List.of()));

        assertThat(normalized.proposal().intent().durationMinutes()).isEqualTo(270L);
    }

    private Generated generated(ActionIntent intent, List<DomainEffectProposal> effects) {
        TurnProposal proposal = new TurnProposal(intent, 60L, "ACTION_PROGRESSED", "有所进展",
                "你依照自己的打算稳步行动，周围的局势也留下了可以继续追查的痕迹。",
                List.of(), effects, List.of());
        return new Generated(proposal, "DEEPSEEK", "deepseek-v4-flash");
    }

    private ActionIntent intent(String type, String abilityId) {
        return new ActionIntent(type, "ATTEMPT", null, null, null, List.of(), List.of(abilityId),
                null, false, List.of(), "尝试行动");
    }

    private TurnNarrativeContext context() {
        IntentContext authority = new IntentContext("user", "save", "operation", 0, "调息",
                new ProtagonistFacts("hero", "照川", "SPIRIT", "MENG_LING", "萌灵",
                        "JU_LING", "聚灵", "尚稳", "寻找出路"),
                "mine", "照骨矿", 0, List.of(),
                List.of(new EntityCandidate("mine", "照骨矿", "LOCATION", "ACCESSIBLE")), List.of(),
                List.of(new EntityCandidate("ability-1", "听石", "TALENT", "ACTIVE")),
                List.of(), List.of(), List.of(new EntityCandidate("ability-definition-1", "听石",
                        "ABILITY_DEFINITION", "LEARNABLE")));
        return new TurnNarrativeContext("玄墟界", "NORMAL", authority, List.of(), List.of(),
                List.of(new TurnNarrativeContext.ActiveEvent("event-1", "矿难", "SEED", "照骨矿", "矿声异常")));
    }
}
