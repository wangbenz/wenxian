package com.wenxian.game.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharacterNameServiceTest {
    private final CharacterNameService service = new CharacterNameService();

    @Test
    void generatesCultivationNameAndRejectsGarbage() {
        var generated = service.generate("HUMAN", "MALE", List.of());
        assertTrue(service.validate(generated.name()).valid());
        assertFalse(service.validate("12334535").valid());
        assertFalse(service.validate("管理员").valid());
    }

    @Test
    void respectsGenderAndRaceWhenGeneratingNames() {
        var male = service.generate("HUMAN", "MALE", List.of());
        var female = service.generate("HUMAN", "FEMALE", List.of());
        var spirit = service.generate("SPIRIT", "UNSPECIFIED", List.of());

        Set<String> maleNames = Stream.concat(Stream.of(male.name()), male.alternatives().stream()).collect(Collectors.toSet());
        Set<String> femaleNames = Stream.concat(Stream.of(female.name()), female.alternatives().stream()).collect(Collectors.toSet());
        Set<String> spiritNames = Stream.concat(Stream.of(spirit.name()), spirit.alternatives().stream()).collect(Collectors.toSet());

        assertTrue(maleNames.stream().noneMatch(femaleNames::contains));
        assertTrue(spiritNames.stream().noneMatch(maleNames::contains));
        assertTrue(spiritNames.stream().noneMatch(femaleNames::contains));
        assertThrows(IllegalArgumentException.class, () -> service.generate("HUMAN", "", List.of()));
    }
}
