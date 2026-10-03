package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterCreationCatalogServiceTest {
    private final CharacterCreationCatalogService service = com.wenxian.game.story.worldpackage.WorldPackageRegistryTestSupport.catalog(new ObjectMapper());

    @Test
    void exposesSingleWorldRaceSemanticsAndRealmScale() {
        var catalog = service.current();

        var world = catalog.world();
        assertThat(world.packageId()).isEqualTo("XUANXU");
        assertThat(world.revision()).isEqualTo(2);
        assertThat(world.races()).extracting("raceId")
                .containsExactly("race:xuanxu:human", "race:xuanxu:yao", "race:xuanxu:spirit");
        assertThat(world.races()).allSatisfy(race -> {
            assertThat(race.description()).isNotBlank();
            assertThat(race.identityFacts()).isNotEmpty();
            assertThat(race.socialHooks()).isNotEmpty();
        });
        assertThat(world.realmScale().ranks()).hasSize(8);
        assertThat(world.realmScale().phases()).containsExactly("EARLY", "MIDDLE", "LATE", "PERFECT");
        assertThat(world.realmScale().ranks().getFirst().raceRealms()).extracting("name")
                .containsExactly("炼气", "开智", "启灵");
    }

    @Test
    void exposesStablePlayPreferenceOptionsWithImpactDescriptions() {
        var preferences = service.current().playPreferences();

        assertThat(preferences.focus()).extracting("code")
                .containsExactly("EXPLORATION", "RELATIONSHIP", "CULTIVATION", "MANAGEMENT", "COMBAT");
        assertThat(preferences.combatFrequency()).extracting("code")
                .containsExactly("LOW", "BALANCED", "HIGH");
        assertThat(preferences.pace()).extracting("code")
                .containsExactly("RELAXED", "BALANCED", "FAST");
        assertThat(preferences.guidance()).extracting("code")
                .containsExactly("LIGHT", "BALANCED", "EXPLICIT");
        assertThat(preferences.defaultFocus()).containsExactly("EXPLORATION", "CULTIVATION");
        assertThat(preferences.defaultCombatFrequency()).isEqualTo("BALANCED");
        assertThat(preferences.focus()).allSatisfy(option -> assertThat(option.description()).isNotBlank());
    }
}
