package com.wenxian.game.v014.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014CharacterRouteServiceTest {
    @Test
    void characterCreationAlwaysUsesFlashButKeepsTheSelectedCredential() {
        var selected = new V014ModelPreferenceService.ResolvedRoute(
                "https://api.deepseek.com", "private-key", "DEEPSEEK", "OPENAI_COMPATIBLE",
                "deepseek-v4-pro", "USER", true);

        var route = V014CharacterRouteService.lightweight(selected);

        assertEquals("deepseek-v4-flash", route.model());
        assertEquals("private-key", route.apiKey());
        assertEquals("USER", route.credentialSource());
        assertTrue(route.hasCustomCredential());
    }
}
