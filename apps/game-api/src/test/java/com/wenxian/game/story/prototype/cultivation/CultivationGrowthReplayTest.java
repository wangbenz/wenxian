package com.wenxian.game.story.prototype.cultivation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.wenxian.game.story.agent.ai.NarrativeWriterAi.NarrativeDraft;
import com.wenxian.game.story.worldpackage.application.WorldPackageIngestionPipeline;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthPrototype.*;
import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthReplay.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定合成样本验证实验宿主的包绑定、证据引用、整轮接受和检查点重载边界。
 * 不调用模型、不启动数据库，也不将结构合法的经历描述当作已通过语义验证的剧情事实。
 */
class CultivationGrowthReplayTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CultivationGrowthReplay replay = new CultivationGrowthReplay(mapper);

    @Test
    void bindsOfficialBundleHashAndItsAdjacentStartingStep() {
        Checkpoint initial = replay.initial();
        var inspected = new WorldPackageIngestionPipeline(mapper).inspectDirectory(
                Path.of("src/main/resources/world-packages/official/XUANXU/2"));

        assertThat(initial.worldContentHash()).isEqualTo(inspected.contentHash());
        assertThat(initial.growth().position()).isEqualTo(
                new Position("realm:xuanxu:human:l1", "phase:xuanxu:early"));
        Map<String, Object> input = replay.directorInput(initial, "原地翻阅吐纳诀，不尝试突破。");
        assertThat(input.get("legalNextPosition")).isEqualTo(
                new Position("realm:xuanxu:human:l1", "phase:xuanxu:middle"));
        assertThat(input.get("worldContext")).isNotNull();
        assertThat(input).doesNotContainKey("pace");
    }

    @Test
    void assignsEventIdentityAndResolvesLocalReferencesWithoutMutatingBefore() {
        Checkpoint before = replay.initial();
        var prepared = replay.prepare(before, "原地练习吐纳。", practice("breath", "初次找到舒适节奏"));

        assertThat(prepared.outcome().experiences()).singleElement().satisfies(event -> {
            assertThat(event.id()).isEqualTo("experience:replay:1:1");
            assertThat(event.turn()).isEqualTo(1);
            assertThat(event.abilityRefs()).containsExactly(ABILITY);
        });
        assertThat(prepared.after().learning().get(ABILITY).evidenceRefs())
                .containsExactly("experience:replay:1:1");
        assertThat(before.growth().revision()).isZero();
        assertThat(before.experiences()).isEmpty();
        assertThat(before.turns()).isEmpty();
    }

    @Test
    void acceptsNamespacedLocalReferenceWithoutLettingItChooseHostEventIdentity() {
        var prepared = replay.prepare(replay.initial(), "短暂尝试吐纳。", practice("exp:breathing:first-try", "找到舒适节奏"));
        assertThat(prepared.outcome().experiences()).extracting(ExperienceRecord::id)
                .containsExactly("experience:replay:1:1");
        assertThat(prepared.after().learning().get(ABILITY).evidenceRefs()).containsExactly("experience:replay:1:1");

        var hostShapedAlias = replay.prepare(replay.initial(), "短暂尝试吐纳。",
                practice("experience:replay:99:7", "找到舒适节奏"));
        assertThat(hostShapedAlias.outcome().experiences()).extracting(ExperienceRecord::id)
                .containsExactly("experience:replay:1:1");
        assertThat(hostShapedAlias.after().learning().get(ABILITY).evidenceRefs()).containsExactly("experience:replay:1:1");
    }

    @Test
    void cannotInjectTrustedMetadataThroughDirectorExperienceJson() {
        var encoded = mapper.valueToTree(practice("breath", "初次找到节奏"));
        var event = (com.fasterxml.jackson.databind.node.ObjectNode) encoded.path("experiences").get(0);
        event.put("acceptance", "COMMITTED");
        event.put("turn", 0);
        event.put("saveId", "another-save");

        assertThatThrownBy(() -> mapper.treeToValue(encoded, DirectorDraft.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void exactOldObservationsAreNoOpsButChangedGrowthStillRequiresThisTurnEvidence() {
        var first = practice("breath", "找到放松节奏");
        var before = advance(replay.initial(), new DirectorDraft(first.resolution(), first.outcomeSummary(),
                first.elapsedTime(), first.experiences(), new GrowthDraft(first.growth().learning(),
                new ObservationDraft("练过一次", "还不稳定", List.of("breath")), none()),
                first.sceneBeats(), first.stoppingPoint()));
        var oldLearning = before.growth().learning().get(ABILITY);
        var oldAccumulation = before.growth().accumulation();
        var repeated = new LearningDraft(ABILITY, oldLearning.achieved(), oldLearning.limitations(),
                List.copyOf(oldLearning.evidenceRefs()));
        var repeatedAccumulation = new ObservationDraft(oldAccumulation.achieved(), oldAccumulation.limitations(),
                List.copyOf(oldAccumulation.evidenceRefs()));
        var walking = practice("walk", "尝试行走时保持呼吸，尚无新掌握");
        var draft = new DirectorDraft(walking.resolution(), walking.outcomeSummary(), walking.elapsedTime(),
                walking.experiences(), new GrowthDraft(List.of(repeated), repeatedAccumulation, none()),
                walking.sceneBeats(), walking.stoppingPoint());
        var completed = advance(before, draft);
        assertThat(completed.growth().learning()).isEqualTo(before.growth().learning());
        assertThat(completed.growth().accumulation()).isEqualTo(before.growth().accumulation());
        assertThat(completed.experiences()).hasSize(2);
        assertThat(completed.turns().get(1).proposed()).isEqualTo(draft);
        var duplicateNoOps = new GrowthDraft(List.of(repeated, repeated), repeatedAccumulation, none());
        assertThatThrownBy(() -> advance(before, new DirectorDraft(draft.resolution(), draft.outcomeSummary(),
                draft.elapsedTime(), draft.experiences(), duplicateNoOps, draft.sceneBeats(), draft.stoppingPoint())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("duplicate learning ability");
        var whitespaceAlias = new LearningDraft(" " + ABILITY + " ", repeated.achieved(), repeated.limitations(),
                repeated.evidenceRefs());
        var duplicateAliases = new GrowthDraft(List.of(repeated, whitespaceAlias), repeatedAccumulation, none());
        assertThatThrownBy(() -> advance(before, new DirectorDraft(draft.resolution(), draft.outcomeSummary(),
                draft.elapsedTime(), draft.experiences(), duplicateAliases, draft.sceneBeats(), draft.stoppingPoint())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("duplicate learning ability");

        var unsupported = new GrowthDraft(List.of(new LearningDraft(ABILITY, "声称学会新的运用",
                oldLearning.limitations(), repeated.evidenceRefs())), repeatedAccumulation, none());
        assertThatThrownBy(() -> advance(before, new DirectorDraft(draft.resolution(), draft.outcomeSummary(),
                draft.elapsedTime(), draft.experiences(), unsupported, draft.sceneBeats(), draft.stoppingPoint())))
                .isInstanceOf(Rejected.class).hasMessage("INVALID_EVIDENCE");
        var unsupportedAccumulation = new GrowthDraft(List.of(repeated),
                new ObservationDraft("声称更多积累", oldAccumulation.limitations(), repeatedAccumulation.evidenceRefs()), none());
        assertThatThrownBy(() -> advance(before, new DirectorDraft(draft.resolution(), draft.outcomeSummary(),
                draft.elapsedTime(), draft.experiences(), unsupportedAccumulation, draft.sceneBeats(), draft.stoppingPoint())))
                .isInstanceOf(Rejected.class).hasMessage("INVALID_EVIDENCE");
    }

    @Test
    void rejectsHistoryImpersonationAndUnknownEvidenceWithoutFallback() {
        Checkpoint before = advance(replay.initial(), practice("breath", "初次找到节奏"));
        DirectorDraft impersonation = practice("experience:replay:1:1", "将过去说成本轮");
        assertThatThrownBy(() -> replay.prepare(before, "继续练习。", impersonation))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid local event reference");

        DirectorDraft dangling = new DirectorDraft(Resolution.PARTIAL, "仍在摸索。", "一盏茶", List.of(),
                new GrowthDraft(List.of(new LearningDraft(ABILITY, "声称已经进步", "快步仍会打乱气息",
                        List.of("experience:unknown"))), null, none()), List.of("练习后停下"), "练功廊停下");
        assertThatThrownBy(() -> replay.prepare(before, "继续练习。", dangling))
                .isInstanceOf(Rejected.class).hasMessage("INVALID_EVIDENCE");
        assertThat(before.experiences()).hasSize(1);
    }

    @Test
    void rejectsUnknownAbilityInEitherExperienceOrLearning() {
        DirectorDraft draft = practice("breath", "找到节奏");
        DirectorDraft unknownEvent = new DirectorDraft(draft.resolution(), draft.outcomeSummary(), draft.elapsedTime(),
                List.of(new ExperienceDraft("breath", "练习陌生功法", List.of("ability:unknown"))),
                draft.growth(), draft.sceneBeats(), draft.stoppingPoint());
        assertThatThrownBy(() -> replay.prepare(replay.initial(), "练习。", unknownEvent))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("experience references unknown ability");

        DirectorDraft unknownLearning = new DirectorDraft(draft.resolution(), draft.outcomeSummary(), draft.elapsedTime(),
                draft.experiences(), new GrowthDraft(List.of(new LearningDraft("ability:unknown", "练习新能力",
                "仍需练习", List.of("breath"))), null, none()), draft.sceneBeats(), draft.stoppingPoint());
        assertThatThrownBy(() -> replay.prepare(replay.initial(), "练习。", unknownLearning))
                .isInstanceOf(Rejected.class).hasMessage("INVALID_PROPOSAL");
    }

    @Test
    void nextTurnReceivesSameAbilityAndEveryAcceptedExperience() {
        Checkpoint first = advance(replay.initial(), practice("breath", "初次找到节奏"));
        Checkpoint second = advance(first, practice("breath", "重复练习后更易找回节奏"));
        var input = replay.directorInput(second, "尝试缓步吐纳。");

        assertThat(input.get("acceptedExperiences")).isEqualTo(second.experiences());
        assertThat(second.experiences()).extracting(ExperienceRecord::id)
                .containsExactly("experience:replay:1:1", "experience:replay:2:1");
        assertThat(second.growth().learning()).containsOnlyKeys(ABILITY);
        assertThat(second.growth().learning().get(ABILITY).evidenceRefs())
                .containsExactlyInAnyOrder("experience:replay:1:1", "experience:replay:2:1");
        assertThat((List<?>) input.get("recentTurns")).hasSize(2);
        assertThat(first.experiences()).hasSize(1);
    }

    @Test
    void writerFailureLeavesOriginalCheckpointAndRetryDoesNotLeakEvents() {
        Checkpoint before = replay.initial();
        var prepared = replay.prepare(before, "原地练习。", practice("breath", "找到节奏"));

        assertThatThrownBy(() -> replay.complete(prepared, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("missing prose");
        assertThatThrownBy(() -> replay.complete(prepared, new NarrativeDraft("练习", "", List.of(), "停下练习")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(before.experiences()).isEmpty();
        assertThat(before.turns()).isEmpty();
        assertThat(before.growth().revision()).isZero();

        var retried = replay.prepare(before, "原地练习。", practice("breath", "找到节奏"));
        Checkpoint completed = replay.complete(retried, prose());
        assertThat(completed.experiences()).extracting(ExperienceRecord::id)
                .containsExactly("experience:replay:1:1");
        assertThat(completed.turns()).hasSize(1);
        assertThat(completed.growth().revision()).isEqualTo(1);
    }

    @Test
    void jsonCheckpointReloadPreservesIdentityAndAllowsNextTurn() throws Exception {
        Checkpoint first = advance(replay.initial(), practice("breath", "初次找到节奏"));
        Checkpoint restored = mapper.readValue(mapper.writeValueAsBytes(first), Checkpoint.class);

        // JSON 数值在重载后可能从 LongNode 归一为 IntNode，比较持久化语义而非节点实现类型。
        assertThat(mapper.readTree(mapper.writeValueAsBytes(restored)))
                .isEqualTo(mapper.readTree(mapper.writeValueAsBytes(first)));
        CultivationGrowthReplay restartedHost = new CultivationGrowthReplay(mapper);
        var prepared = restartedHost.prepare(restored, "翌日继续练习。", practice("breath", "更易找回节奏"));
        Checkpoint second = restartedHost.complete(prepared, prose());
        assertThat(second.growth().saveId()).isEqualTo(SAVE);
        assertThat(second.growth().learning()).containsOnlyKeys(ABILITY);
        assertThat(second.experiences()).extracting(ExperienceRecord::id)
                .containsExactly("experience:replay:1:1", "experience:replay:2:1");
        assertThat(second.growth().lastTurn()).isEqualTo(2);
        assertThat(restored.experiences()).hasSize(1);
    }

    @Test
    void stopsAfterSixCompletedTurnsAndRejectsChangedWorldBinding() {
        Checkpoint current = replay.initial();
        for (int turn = 1; turn <= 6; turn++) {
            current = advance(current, practice("breath", "第" + turn + "次练习后找回节奏"));
        }
        Checkpoint six = current;
        assertThat(six.turns()).hasSize(6);
        assertThatThrownBy(() -> replay.directorInput(six, "再练一次。"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("six turn limit");
        Checkpoint initial = replay.initial();
        Checkpoint wrongWorld = new Checkpoint("unrelated-hash", initial.growth(), List.of(), List.of());
        assertThatThrownBy(() -> replay.directorInput(wrongWorld, "练习。"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("world hash mismatch");
    }

    @Test
    void writerReceivesEffectivePreservedQualificationWithoutAdvancingRealm() {
        Checkpoint first = advance(replay.initial(), practice("breath", "找到节奏"));
        Position next = (Position) replay.directorInput(first, "停下检视此前练习。").get("legalNextPosition");
        DirectorDraft ready = new DirectorDraft(Resolution.SUCCEEDED, "检查此前的实际积累。", "片刻", List.of(),
                new GrowthDraft(List.of(), null, new QualificationDraft(Readiness.READY, next,
                        "此前练习已形成准备", List.of("experience:replay:1:1"))), List.of("停下检查"), "不突破");
        Checkpoint second = advance(first, ready);
        var third = replay.prepare(second, "继续普通练习，不尝试晋升。", practice("breath", "再次找回节奏"));

        @SuppressWarnings("unchecked")
        Map<String, Object> writerGrowth = (Map<String, Object>) third.writerInput().get("afterGrowth");
        assertThat(writerGrowth.get("qualification")).isEqualTo(second.growth().qualification());
        assertThat(third.after().position()).isEqualTo(replay.initial().growth().position());
        assertThat(third.after().qualification().target()).isEqualTo(next);
    }

    private Checkpoint advance(Checkpoint before, DirectorDraft draft) {
        return replay.complete(replay.prepare(before, "原地练习吐纳，练习结束后停下。", draft), prose());
    }

    private DirectorDraft practice(String ref, String achieved) {
        return new DirectorDraft(Resolution.PARTIAL, "找到一段舒适节奏，随后停下。", "一盏茶",
                List.of(new ExperienceDraft(ref, achieved, List.of(ABILITY))),
                new GrowthDraft(List.of(new LearningDraft(ABILITY, achieved, "快步仍会打乱气息", List.of(ref))),
                        null, none()), List.of("注意呼吸", "停下练习"), "仍在练功廊，没有尝试突破");
    }

    private QualificationDraft none() {
        return new QualificationDraft(Readiness.NONE, null, "本轮不作资格判断", List.of());
    }

    private NarrativeDraft prose() {
        return new NarrativeDraft("缓息", "你停在练功廊，留意刚才找到的那一段节奏。", List.of(), "练习后停在原地。");
    }
}
