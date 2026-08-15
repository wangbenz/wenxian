package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.Conflict;
import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.persistence.StoryPersistenceModels.EntityAlias;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StoryEntityReferenceCompilerTest {
    private final StoryEntityReferenceCompiler compiler = new StoryEntityReferenceCompiler();

    @Test
    void serverCanonicalizesCopiedNameAndRemovesCustodyFromCharacter() {
        EntityState character = entity("character-fox", "CHARACTER", "涂山瑶");
        EntityReference copied = new EntityReference("涂山月", ReferenceResolution.EXISTING,
                character.id(), "CHARACTER", "涂山月", "TARGET",
                new CustodyValue(HolderType.PLAYER, null));

        StoryActionContract compiled = compiler.compile(projection(character), contract(copied), List.of());

        EntityReference result = compiled.references().getFirst();
        assertEquals("涂山瑶", result.canonicalName());
        assertEquals("CHARACTER", result.entityType());
        assertNull(result.expectedCustody());
    }

    @Test
    void resolvesHistoricalAliasToStableEntityWhenModelOmitsId() {
        EntityState wolf = entity("character-wolf", "CHARACTER", "霜刃");
        EntityAlias alias = new EntityAlias("alias-1", "save-1", wolf.id(), "小冰霜狼", "小冰霜狼",
                "RENAMED_FROM", 8, true, Instant.EPOCH, Instant.EPOCH);
        EntityReference historicalName = new EntityReference("小冰霜狼", ReferenceResolution.EXISTING,
                null, "CHARACTER", "小冰霜狼", "TARGET", null);

        StoryActionContract compiled = compiler.compile(projection(wolf), contract(historicalName), List.of(alias));

        assertEquals(wolf.id(), compiled.references().getFirst().entityId());
        assertEquals("霜刃", compiled.references().getFirst().canonicalName());
    }

    @Test
    void doesNotTurnSourceItemIntoAbilityWhenModelBoundWrongType() {
        EntityState jade = entity("item-jade", "ITEM", "上古玉简");
        EntityReference ability = new EntityReference("苍龙引气诀", ReferenceResolution.EXISTING,
                jade.id(), "ABILITY", "苍龙引气诀", "TARGET", null);

        StoryActionContract compiled = compiler.compile(projection(jade), contract(ability), List.of());

        assertEquals(jade.id(), compiled.references().getFirst().entityId());
        assertEquals("ABILITY", compiled.references().getFirst().entityType());
        assertEquals("苍龙引气诀", compiled.references().getFirst().canonicalName());
    }

    @Test
    void ambiguousAliasIsNotAutomaticallyBound() {
        EntityState first = entity("character-first", "CHARACTER", "无名客甲");
        EntityState second = entity("character-second", "CHARACTER", "无名客乙");
        EntityAlias firstAlias = alias("alias-1", first.id(), "黑衣人");
        EntityAlias secondAlias = alias("alias-2", second.id(), "黑衣人");
        EntityReference reference = new EntityReference("黑衣人", ReferenceResolution.EXISTING,
                null, "CHARACTER", "黑衣人", "TARGET", null);

        StoryActionContract compiled = compiler.compile(projection(first, second), contract(reference),
                List.of(firstAlias, secondAlias));

        assertNull(compiled.references().getFirst().entityId());
    }

    @Test
    void explicitDurationMakesTheContractAFutureAdvanceWithoutGrantingSuccess() {
        StoryActionContract raw = new StoryActionContract("闭关三年尝试突破", false,
                List.of(), List.of(), List.of(), "闭关结束后停下", new Conflict(false, "", ""));

        StoryActionContract compiled = compiler.compile(projection(), raw, List.of(), 1_555_200L);

        assertEquals(StoryActionContract.ActionScope.ADVANCE_FUTURE, compiled.actionScope());
        assertEquals(StoryActionContract.ActionMode.ATTEMPT, compiled.actionMode());
    }

    private EntityAlias alias(String id, String entityId, String name) {
        return new EntityAlias(id, "save-1", entityId, name, name, "SOURCE_NAME", 1,
                true, Instant.EPOCH, Instant.EPOCH);
    }

    private StoryActionContract contract(EntityReference reference) {
        return new StoryActionContract("继续行动", false, List.of(reference), List.of(), List.of(), "",
                new Conflict(false, "", ""));
    }

    private EntityState entity(String id, String type, String name) {
        return new EntityState(id, type, name, "当前", "已确认实体", null,
                true, null, null, null, null);
    }

    private StoryProjection projection(EntityState... entities) {
        Map<String, EntityState> values = java.util.Arrays.stream(entities)
                .collect(java.util.stream.Collectors.toMap(EntityState::id, value -> value));
        return new StoryProjection(0, new CoreState("原地", "平静", "凡俗", "问仙"),
                values, Map.of(), Map.of(), List.of());
    }
}
