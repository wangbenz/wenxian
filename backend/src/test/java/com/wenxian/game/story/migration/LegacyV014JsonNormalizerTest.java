package com.wenxian.game.story.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyV014JsonNormalizerTest {
    private final LegacyV014JsonNormalizer normalizer =
            new LegacyV014JsonNormalizer(new ObjectMapper().findAndRegisterModules());

    @Test
    void acceptsStrictJsonWithoutMarkingRepair() {
        var result = normalizer.normalize("{\"title\":\"山门\",\"items\":[]}");

        assertTrue(result.valid(), result.error() + " " + result.repairCodes());
        assertFalse(result.repaired());
        assertTrue(result.repairCodes().isEmpty());
    }

    @Test
    void repairsUnescapedNewlineInsideString() {
        var result = normalizer.normalize("{\"summary\":\"第一行\n第二行\"}");

        assertTrue(result.valid(), result.error() + " " + result.repairCodes());
        assertTrue(result.repaired());
        assertEquals("第一行\n第二行", result.value().path("summary").asText());
        assertEquals("UNESCAPED_CONTROL_CHARACTER", result.repairCodes().getFirst());
    }

    @Test
    void repairsRepeatedKeyDelimiterMistakesWhenParserPositionsAreUnambiguous() {
        var result = normalizer.normalize("{\"title\",\"山门\",\"status\",\"开启\"}");

        assertTrue(result.valid());
        assertTrue(result.repaired());
        assertEquals("山门", result.value().path("title").asText());
        assertEquals("开启", result.value().path("status").asText());
        assertEquals(2, result.repairCodes().stream()
                .filter("COMMA_REPLACED_BY_COLON"::equals).count());
    }

    @Test
    void appendsOnlyUniqueClosingSuffix() {
        var result = normalizer.normalize("{\"items\":[{\"name\":\"青玉\"}");

        assertTrue(result.valid(), result.error() + " " + result.repairCodes());
        assertTrue(result.repaired());
        assertEquals("青玉", result.value().path("items").get(0).path("name").asText());
        assertTrue(result.repairCodes().contains("UNIQUE_CLOSING_SUFFIX_APPENDED"));
    }

    @Test
    void refusesAmbiguousStructuralDamage() {
        var result = normalizer.normalize("{\"title\",4:5}");

        assertFalse(result.valid());
        assertFalse(result.repaired());
    }
}
