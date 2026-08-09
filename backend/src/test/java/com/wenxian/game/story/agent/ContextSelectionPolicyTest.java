package com.wenxian.game.story.agent;

import com.wenxian.game.story.agent.ContextSelectionPolicy.SelectionSignals;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.PromptContextAssembler.KnowledgeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSelectionPolicyTest {
    private final ContextSelectionPolicy policy = new ContextSelectionPolicy();

    @Test
    void mergesOnlyStructuredStableReferencesFromEachSource() {
        SelectionSignals signals = new SelectionSignals(
                Set.of("character-scene"), Set.of("item-recent"), Set.of("ability-explicit"),
                Set.of("character-semantic"),
                Set.of("world-scene"), Set.of("world-explicit"), Set.of("world-semantic"),
                List.of(new KnowledgeRef("character-scene", "world-scene")),
                List.of(new KnowledgeRef("character-scene", "world-scene"),
                        new KnowledgeRef("protagonist", "character-semantic")),
                List.of(),
                Set.of("memory-recent"), Set.of("memory-explicit"), Set.of("memory-semantic")
        );

        ContextSelection selection = policy.select(signals);

        assertEquals(Set.of("character-scene", "item-recent", "ability-explicit", "character-semantic"),
                selection.relatedEntityIds());
        assertEquals(selection.relatedEntityIds(), selection.entityIndexIds());
        assertEquals(Set.of("world-scene", "world-explicit", "world-semantic"), selection.worldFactIds());
        assertEquals(2, selection.knowledgeRefs().size());
        assertEquals(Set.of("memory-recent", "memory-explicit", "memory-semantic"), selection.memoryIds());
    }

    @Test
    void doesNotCreateReferencesWhenNoStructuredSignalExists() {
        ContextSelection selection = policy.select(null);

        assertTrue(selection.entityIndexIds().isEmpty());
        assertTrue(selection.worldFactIds().isEmpty());
        assertTrue(selection.knowledgeRefs().isEmpty());
        assertTrue(selection.memoryIds().isEmpty());
    }
}
