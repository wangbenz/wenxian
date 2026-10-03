package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.StoryModelSelectionGateway;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.support.StoryTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorySaveViewServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final StoryEntityRepository entities = mock(StoryEntityRepository.class);
    private final StoryMemoryRepository memories = mock(StoryMemoryRepository.class);
    private final StoryModelSelectionGateway selections = mock(StoryModelSelectionGateway.class);
    private final StorySaveViewService views = new StorySaveViewService(entities, memories, selections, mapper);
    private final StoredSave saved = mock(StoredSave.class);

    @BeforeEach
    void prepareSave() {
        ObjectNode state = StoryTestFixtures.state(mapper, "ability-view-save", 5, "讲习处", "已完成练习。");
        state.put("schemaVersion", 2);
        when(saved.id()).thenReturn("ability-view-save");
        when(saved.state()).thenReturn(state);
        when(saved.stateSchemaVersion()).thenReturn(2);
        when(saved.status()).thenReturn("ACTIVE");
        when(saved.turnNumber()).thenReturn(5);
        when(memories.recentActive(saved.id(), 80)).thenReturn(List.of());
        when(selections.currentSelection("view-user")).thenReturn(
                new StoryModelSelectionGateway.StoryModelSelection("TEST", "TEST", "PLATFORM"));
    }

    @ParameterizedTest
    @EnumSource(AbilityState.class)
    void apiReadsEveryTrustedLearningStateSeparatelyFromGenericStatus(AbilityState state) {
        ObjectNode attributes = mapper.createObjectNode();
        attributes.putObject("_trusted").put("abilityState", state.name());
        ObjectNode unchanged = attributes.deepCopy();
        when(entities.listVisible(saved.id())).thenReturn(List.of(ability("ACTIVE", attributes)));

        var runtime = views.read(saved);
        var api = views.response("view-user", saved, runtime, "LIGHT_NARRATIVE").state().abilities().getFirst();

        assertEquals("ACTIVE", api.status(), "学习状态不覆盖旧通用字段");
        assertEquals(state, api.abilityState());
        assertEquals(state.name(), mapper.valueToTree(api).path("abilityState").asText());
        assertEquals(unchanged, attributes, "读视图不能修改持久化内容");
    }

    @ParameterizedTest
    @CsvSource({"KNOWN,KNOWN", "LEARNING,LEARNING", "MASTERED,MASTERED",
            "TEMPORARILY_BLOCKED,TEMPORARILY_BLOCKED", "LOST,LOST", "ACTIVE,UNKNOWN",
            "UNRECOGNIZED_OLD_STATUS,UNKNOWN"})
    void missingTrustedFieldOnlyFallsBackToExplicitLegacyLearningStates(String status, AbilityState expected) {
        when(entities.listVisible(saved.id())).thenReturn(List.of(ability(status, mapper.createObjectNode())));
        assertEquals(expected, views.read(saved).abilities().getFirst().abilityState());
    }

    @Test
    void legacyNullFieldStillPreservesAnExplicitMasteredState() {
        ObjectNode attributes = mapper.createObjectNode();
        attributes.putObject("_trusted").putNull("abilityState");
        when(entities.listVisible(saved.id())).thenReturn(List.of(ability("MASTERED", attributes)));
        assertEquals(AbilityState.MASTERED, views.read(saved).abilities().getFirst().abilityState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"FUTURE_STATE\"", "\"\"", "{}", "3"})
    void invalidTrustedValueDoesNotBorrowLegacyMasteryOrBreakTheSave(String storedJson) throws Exception {
        ObjectNode attributes = mapper.createObjectNode();
        attributes.putObject("_trusted").set("abilityState", mapper.readTree(storedJson));
        when(entities.listVisible(saved.id())).thenReturn(List.of(ability("MASTERED", attributes)));
        assertEquals(AbilityState.UNKNOWN, views.read(saved).abilities().getFirst().abilityState());
    }

    @Test
    void reloadingTheSameAbilityExposesLearningInsteadOfTheUnchangedActiveStatus() {
        ObjectNode known = mapper.createObjectNode();
        known.putObject("_trusted").put("abilityState", "KNOWN");
        ObjectNode learning = mapper.createObjectNode();
        learning.putObject("_trusted").put("abilityState", "LEARNING");
        when(entities.listVisible(saved.id())).thenReturn(List.of(ability("ACTIVE", known)),
                List.of(ability("ACTIVE", learning)));

        var first = views.response("view-user", saved, views.read(saved), "LIGHT_NARRATIVE")
                .state().abilities().getFirst();
        var reloaded = views.response("view-user", saved, views.read(saved), "LIGHT_NARRATIVE")
                .state().abilities().getFirst();

        assertEquals(first.id(), reloaded.id());
        assertEquals(first.status(), reloaded.status());
        assertEquals(AbilityState.KNOWN, first.abilityState());
        assertEquals(AbilityState.LEARNING, reloaded.abilityState());
    }

    private Entity ability(String status, ObjectNode attributes) {
        return new Entity("ability-breathing", "ability-view-save", "ABILITY", "调息口诀", status, true,
                "已有指点，正在尝试独立运用。", attributes, 4, 5, Instant.EPOCH, Instant.EPOCH);
    }
}
