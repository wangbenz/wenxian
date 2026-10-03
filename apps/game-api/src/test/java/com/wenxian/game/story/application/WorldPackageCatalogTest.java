package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldPackageCatalogTest {
    private final WorldPackageCatalog catalog = new WorldPackageCatalog(new ObjectMapper());

    @Test
    void loadsTheSingleCurrentWorldAndSharedRealmScale() {
        var xuanxu = catalog.currentPackage();
        assertThat(xuanxu.packageId()).isEqualTo("XUANXU");
        assertThat(xuanxu.revision()).isEqualTo(2);
        assertThat(xuanxu.races()).extracting("raceId")
                .containsExactly("race:xuanxu:human", "race:xuanxu:yao", "race:xuanxu:spirit");
        assertThat(xuanxu.realmScale().ranks()).extracting("rankKey")
                .containsExactly("L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8");
        assertThat(xuanxu.realmScale().startingStage("race:xuanxu:human").name()).isEqualTo("炼气");
        assertThat(xuanxu.defaultOpeningRegion().regionId()).isEqualTo("region:xuanxu:qingqi");
        assertThat(xuanxu.coreRules()).isNotEmpty();
        assertThat(xuanxu.loreEntries()).isNotEmpty();
    }

    @Test
    void resolvesAnExactRevisionAndRejectsUnknownContent() {
        assertThat(catalog.resolve("XUANXU", 2)).isEqualTo(catalog.currentPackage());
        assertThatThrownBy(() -> catalog.resolve("XUANXU", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("没有找到所选世界包修订");
        assertThatThrownBy(() -> catalog.resolveCurrent("CUSTOM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("没有找到所选世界包");
    }
}
