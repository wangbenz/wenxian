package com.wenxian.game.model.application;

import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.persistence.ModelCatalogRepository;
import com.wenxian.game.model.persistence.ServicePlanRuntimeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServicePlanRuntimeServiceTest {
    private final ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
    private final ServicePlanRuntimeRepository plans = mock(ServicePlanRuntimeRepository.class);

    @Test
    void freezesPreferredTierAndKeepsPlayerPayloadFreeOfModelNames() {
        when(plans.findActivePlan()).thenReturn(Optional.of(
                new ServicePlanRuntimeRepository.ActivePlan("plan-v3", "BALANCED")));
        when(plans.findEnabledTiers("plan-v3")).thenReturn(List.of(
                tier("LIGHT", "轻量", 1), tier("BALANCED", "均衡", 2), tier("DEEP", "深度", 4)));
        when(plans.findUserTierPreference("user-1")).thenReturn(Optional.of("DEEP"));
        ServicePlanRuntimeService service = new ServicePlanRuntimeService(catalog, plans, true);

        var frozen = service.freezeForUser("user-1").orElseThrow();
        var playerTiers = service.playerTiers("user-1");

        assertThat(frozen.planVersionId()).isEqualTo("plan-v3");
        assertThat(frozen.tierCode()).isEqualTo("DEEP");
        assertThat(frozen.pointCost()).isEqualTo(4);
        assertThat(playerTiers).extracting(ServicePlanRuntimeService.PlayerTier::code)
                .containsExactly("LIGHT", "BALANCED", "DEEP");
        assertThat(playerTiers).filteredOn(ServicePlanRuntimeService.PlayerTier::selected)
                .extracting(ServicePlanRuntimeService.PlayerTier::name).containsExactly("深度");
    }

    @Test
    void personalCredentialNeverLoadsOrChargesAPlatformPlan() {
        when(catalog.findUserPreference("user-2")).thenReturn(Optional.of(
                new ModelCatalogRepository.UserPreference("DEEPSEEK", "flash", "USER")));
        ServicePlanRuntimeService service = new ServicePlanRuntimeService(catalog, plans, true);

        assertThat(service.freezeForUser("user-2")).isEmpty();
        verify(plans, never()).findActivePlan();
    }

    @Test
    void selectionWritesOnlyAStableTierCode() {
        when(plans.findActivePlan()).thenReturn(Optional.of(
                new ServicePlanRuntimeRepository.ActivePlan("plan-v4", "BALANCED")));
        when(plans.findEnabledTiers("plan-v4")).thenReturn(List.of(tier("DEEP", "深度", 5)));
        ServicePlanRuntimeService service = new ServicePlanRuntimeService(catalog, plans, true);

        service.selectTier("user-3", "deep");

        verify(plans).saveUserTierPreference("user-3", "DEEP");
    }

    private ServicePlanRuntimeRepository.Tier tier(String code, String name, int cost) {
        return new ServicePlanRuntimeRepository.Tier(code, name, name + "说明", cost);
    }
}
