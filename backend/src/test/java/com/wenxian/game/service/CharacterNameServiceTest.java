package com.wenxian.game.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterNameServiceTest {
    private final CharacterNameService service = new CharacterNameService();

    @Test
    void generatesCultivationNameAndRejectsGarbage() {
        var generated = service.generate("HUMAN", List.of());
        assertTrue(service.validate(generated.name()).valid());
        assertFalse(service.validate("12334535").valid());
        assertFalse(service.validate("管理员").valid());
    }
}
