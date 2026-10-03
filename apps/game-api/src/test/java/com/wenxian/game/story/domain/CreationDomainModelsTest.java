package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.wenxian.game.story.domain.CreationContract.OriginFactType.PAST_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreationDomainModelsTest {
    @Test
    void creationContractSeparatesEstablishedFactsHooksAndEntityReferences() {
        var contract = new CreationContract(
                new CreationContract.WorldBinding("XUANXU", 1, "玄墟界", "仙路真假难辨",
                        "region:xuanxu:qingqi"),
                new CreationContract.CharacterIdentity("沈砚", "MALE", "race:xuanxu:human", "人族"),
                new CreationContract.CharacterOrigin("被逐出宗门的外门弟子",
                        List.of(new CreationContract.OriginFact("origin:fact:1", PAST_EVENT,
                                "曾是青玄宗外门弟子")),
                        List.of(new CreationContract.StoryHook("origin:hook:1", "被逐原因尚未查清")),
                        List.of("item:broken-token")),
                new CreationContract.Opening("region:xuanxu:qingqi", "刚刚离开宗门山门"),
                new CreationContract.RealmBinding("realm-scale:xuanxu", 1, "L1",
                        WorldPackage.RealmPhase.EARLY));

        assertThat(contract.origin().establishedFacts()).extracting("statement")
                .containsExactly("曾是青玄宗外门弟子");
        assertThat(contract.origin().openHooks()).extracting("summary")
                .containsExactly("被逐原因尚未查清");
        assertThat(contract.origin().startingEntityRefs()).containsExactly("item:broken-token");
    }

    @Test
    void creationContractRejectsDuplicateLocalIdentities() {
        assertThatThrownBy(() -> new CreationContract.CharacterOrigin("旧事未明",
                List.of(
                        new CreationContract.OriginFact("origin:fact:1", PAST_EVENT, "曾入山门"),
                        new CreationContract.OriginFact("origin:fact:1", PAST_EVENT, "后来离开")),
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("根源事实 ID重复");
    }

    @Test
    void playPreferenceIsIndependentAndDeduplicatesFocusWithoutLosingOrder() {
        Instant updatedAt = Instant.parse("2026-08-22T12:00:00Z");
        var preference = new PlayPreference(
                List.of(PlayPreference.Focus.RELATIONSHIP, PlayPreference.Focus.EXPLORATION,
                        PlayPreference.Focus.RELATIONSHIP),
                PlayPreference.CombatFrequency.LOW,
                PlayPreference.Pace.RELAXED,
                PlayPreference.Guidance.LIGHT,
                "  少一些机械受难  ", updatedAt);

        assertThat(preference.focus()).containsExactly(
                PlayPreference.Focus.RELATIONSHIP, PlayPreference.Focus.EXPLORATION);
        assertThat(preference.additionalNote()).isEqualTo("少一些机械受难");
        assertThat(preference.updatedAt()).isEqualTo(updatedAt);
    }
}
