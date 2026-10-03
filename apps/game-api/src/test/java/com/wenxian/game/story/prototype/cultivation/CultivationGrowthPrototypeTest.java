package com.wenxian.game.story.prototype.cultivation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthPrototype.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 固定样本验证成长原型的结构边界；所有事件均为合成的宿主输入，不验证模型语义或实际剧情质量。
 * 不启动 Spring、数据库或模型，不改变现有成长策略，也不验证尚未确定的晋升与失格规则。
 */
class CultivationGrowthPrototypeTest {
    private static final String SAVE = "synthetic-growth-save";
    private static final String BREATHING = "ability:breathing";
    private static final String WALKING = "ability:walking";
    private static final Position CURRENT = new Position("qi", "early");
    private static final Position NEXT = new Position("qi", "middle");
    private static final Position LATER = new Position("qi", "late");

    private final CultivationGrowthPrototype prototype = new CultivationGrowthPrototype();

    @Test
    void canonicalPayloadRetryReturnsSameCandidateWithoutApplyingAgain() {
        State before = initial();
        Context context = context(1, Map.of("e1", experience("e1", 1, Acceptance.ACCEPTED_THIS_TURN,
                Set.of(BREATHING, WALKING)), "e2", experience("e2", 1, Acceptance.ACCEPTED_THIS_TURN,
                Set.of(BREATHING, WALKING))));
        Proposal original = new Proposal(List.of(
                learning(WALKING, "负重时仍可缓步", "仍不能奔跑", "e2", "e1"),
                learning(BREATHING, "缓步可维持吐纳", "加快脚步仍会散乱", "e1", "e2")),
                null, none());
        Request request = request("request-1", 0, 1, original);
        State accepted = prototype.propose(before, context, request).candidate();
        Proposal reordered = new Proposal(List.of(
                learning(" " + BREATHING + " ", " 缓步可维持吐纳 ", "加快脚步仍会散乱", " e2 ", "e1"),
                learning(WALKING, "负重时仍可缓步", "仍不能奔跑", "e1", "e2")),
                null, none());

        Result retry = prototype.propose(accepted, context,
                request("request-1", 0, 1, reordered));

        assertThat(retry.replay()).isTrue();
        assertThat(retry.candidate()).isSameAs(accepted);
        assertThat(accepted.revision()).isEqualTo(1);
        assertThat(accepted.learning()).hasSize(2);
        assertThat(accepted.learning().get(BREATHING).evidenceRefs()).containsExactlyInAnyOrder("e1", "e2");
    }

    @Test
    void sameRequestKeyWithChangedPayloadIsAConflict() {
        Context context = context(1, Map.of("e1", currentExperience("e1", 1)));
        State accepted = prototype.propose(initial(), context,
                request("request-1", 0, 1, practice("e1"))).candidate();
        Proposal changed = new Proposal(List.of(learning(BREATHING, "无需专注也能吐纳", "仍不能奔跑", "e1")),
                null, none());

        assertRejected(Failure.REQUEST_CONFLICT,
                () -> prototype.propose(accepted, context, request("request-1", 0, 1, changed)));
        assertThat(accepted.learning().get(BREATHING).achieved()).isEqualTo("缓步可维持吐纳");
        assertThat(accepted.revision()).isEqualTo(1);
    }

    @Test
    void olderRequestAfterAnotherAcceptedTurnIsStaleAndDoesNotApplyAgain() {
        Request first = request("request-1", 0, 1, practice("e1"));
        Context firstContext = context(1, Map.of("e1", currentExperience("e1", 1)));
        State afterFirst = prototype.propose(initial(), firstContext, first).candidate();
        Context secondContext = context(2, Map.of("e2", currentExperience("e2", 2)));
        State afterSecond = prototype.propose(afterFirst, secondContext,
                request("request-2", 1, 2, practice("e2"))).candidate();

        assertRejected(Failure.STALE, () -> prototype.propose(afterSecond, firstContext, first));
        assertThat(afterSecond.revision()).isEqualTo(2);
        assertThat(afterSecond.learning().get(BREATHING).evidenceRefs()).containsExactlyInAnyOrder("e1", "e2");
        assertThat(afterSecond.lastReceipt().request().key()).isEqualTo("request-2");
    }

    @Test
    void identicalPracticeDescriptionInANewTurnWithANewEventIsLegitimate() {
        State afterFirst = prototype.propose(initial(), context(1, Map.of("e1", currentExperience("e1", 1))),
                request("request-1", 0, 1, practice("e1"))).candidate();

        Result second = prototype.propose(afterFirst, context(2, Map.of("e2", currentExperience("e2", 2))),
                request("request-2", 1, 2, practice("e2")));

        assertThat(second.replay()).isFalse();
        assertThat(second.candidate().learning()).hasSize(1);
        assertThat(second.candidate().learning().get(BREATHING).achieved()).isEqualTo("缓步可维持吐纳");
        assertThat(second.candidate().learning().get(BREATHING).evidenceRefs()).containsExactlyInAnyOrder("e1", "e2");
        assertThat(second.candidate().accumulation().evidenceRefs()).containsExactlyInAnyOrder("e1", "e2");
        assertThat(second.candidate().qualification()).isNull();
    }

    @Test
    void oldExperienceAloneCannotPretendThatAnotherPracticeOccurred() {
        State afterFirst = prototype.propose(initial(), context(1, Map.of("e1", currentExperience("e1", 1))),
                request("request-1", 0, 1, practice("e1"))).candidate();
        Context secondContext = context(2, Map.of("e1", committedExperience("e1", 1)));

        assertRejected(Failure.INVALID_EVIDENCE, () -> prototype.propose(afterFirst, secondContext,
                request("request-2", 1, 2, practice("e1"))));
        assertThat(afterFirst.learning().get(BREATHING).evidenceRefs()).containsExactly("e1");
        assertThat(afterFirst.revision()).isEqualTo(1);
    }

    @Test
    void accumulationWithoutLearningAlsoRequiresANewActualExperience() {
        State afterFirst = prototype.propose(initial(), context(1, Map.of("e1", currentExperience("e1", 1))),
                request("request-1", 0, 1, practice("e1"))).candidate();
        Proposal historicalOnly = new Proposal(List.of(),
                observation("声称又形成一次积累", "没有本轮实际修炼事件", "e1"), none());

        assertRejected(Failure.INVALID_EVIDENCE, () -> prototype.propose(afterFirst,
                context(2, Map.of("e1", committedExperience("e1", 1))),
                request("request-2", 1, 2, historicalOnly)));
        assertThat(afterFirst.accumulation().evidenceRefs()).containsExactly("e1");
        assertThat(afterFirst.revision()).isEqualTo(1);
    }

    @Test
    void failedEscortCanYieldSpecificLearningWithoutAccumulationOrQualification() {
        // 总行动失败已经由宿主确认；该原型只接收独立的学习结果，不负责判断失败是否应当有收获。
        Proposal learningInFailedEscort = new Proposal(List.of(learning(BREATHING,
                "护送虽失败，但尝试中学会负重时稳住一轮气息", "仍需专注且不能长时间维持", "failed-escort")),
                null, none());

        State result = prototype.propose(initial(), context(1,
                        Map.of("failed-escort", currentExperience("failed-escort", 1))),
                request("request-1", 0, 1, learningInFailedEscort)).candidate();

        assertThat(result.learning().get(BREATHING).achieved()).contains("护送虽失败");
        assertThat(result.accumulation()).isNull();
        assertThat(result.qualification()).isNull();
        assertThat(result.position()).isEqualTo(CURRENT);
    }

    @Test
    void noGrowthProposalDoesNotManufactureAConsolationReward() {
        State result = prototype.propose(initial(), context(1, Map.of()),
                request("request-1", 0, 1, emptyProposal())).candidate();

        assertThat(result.learning()).isEmpty();
        assertThat(result.accumulation()).isNull();
        assertThat(result.qualification()).isNull();
        assertThat(result.position()).isEqualTo(CURRENT);
    }

    @Test
    void experienceCanSupportLearningAndAccumulationThenLaterQualification() {
        State afterPractice = prototype.propose(initial(), context(1, Map.of("e1", currentExperience("e1", 1))),
                request("practice", 0, 1, practice("e1"))).candidate();
        Proposal qualifyLater = new Proposal(List.of(), null, ready("e1"));

        State qualified = prototype.propose(afterPractice, context(2, Map.of("e1", committedExperience("e1", 1))),
                request("qualify", 1, 2, qualifyLater)).candidate();

        assertThat(qualified.learning()).isEqualTo(afterPractice.learning());
        assertThat(qualified.accumulation()).isEqualTo(afterPractice.accumulation());
        assertThat(qualified.qualification().evidenceRefs()).containsExactly("e1");
        assertThat(qualified.qualification().grantedTurn()).isEqualTo(2);
        assertThat(qualified.hasUnblockedQualification()).isTrue();
        assertThat(qualified.position()).isEqualTo(CURRENT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEvidenceSources")
    void invalidEvidenceCannotBecomeQualification(String scenario, Evidence event) {
        Context context = context(2, Map.of("e1", event));
        State before = emptyState(1, 1);

        assertRejected(Failure.INVALID_EVIDENCE, () -> prototype.propose(before, context,
                request("request-2", 1, 2, new Proposal(List.of(), null, ready("e1")))));
        assertThat(before.qualification()).isNull();
        assertThat(before.revision()).isEqualTo(1);
    }

    private static Stream<Arguments> invalidEvidenceSources() {
        return Stream.of(
                Arguments.of("other save", event("e1", "other-save", 1, CURRENT,
                        EvidenceKind.EXPERIENCE, Acceptance.COMMITTED)),
                Arguments.of("future event", event("e1", SAVE, 3, CURRENT,
                        EvidenceKind.EXPERIENCE, Acceptance.ACCEPTED_THIS_TURN)),
                Arguments.of("unaccepted result", event("e1", SAVE, 2, CURRENT,
                        EvidenceKind.EXPERIENCE, Acceptance.UNACCEPTED)),
                Arguments.of("character assertion", event("e1", SAVE, 1, CURRENT,
                        EvidenceKind.ASSERTION, Acceptance.COMMITTED)),
                Arguments.of("plan", event("e1", SAVE, 1, CURRENT,
                        EvidenceKind.PLAN, Acceptance.COMMITTED)),
                Arguments.of("qualification self reference", event("e1", SAVE, 1, CURRENT,
                        EvidenceKind.QUALIFICATION, Acceptance.COMMITTED)),
                Arguments.of("constraint alone", event("e1", SAVE, 1, CURRENT,
                        EvidenceKind.CONSTRAINT, Acceptance.COMMITTED)),
                Arguments.of("different current stage", event("e1", SAVE, 1, NEXT,
                        EvidenceKind.EXPERIENCE, Acceptance.COMMITTED)),
                Arguments.of("directory key disagrees with event id", event("other-id", SAVE, 1, CURRENT,
                        EvidenceKind.EXPERIENCE, Acceptance.COMMITTED)),
                Arguments.of("old event still marked accepted this turn", event("e1", SAVE, 1, CURRENT,
                        EvidenceKind.EXPERIENCE, Acceptance.ACCEPTED_THIS_TURN)),
                Arguments.of("uncommitted current event marked historical", event("e1", SAVE, 2, CURRENT,
                        EvidenceKind.EXPERIENCE, Acceptance.COMMITTED)));
    }

    @Test
    void missingEvidenceDoesNotFallBackToPlayerAction() {
        State before = initial();

        assertRejected(Failure.INVALID_EVIDENCE, () -> prototype.propose(before, context(1, Map.of()),
                request("request-1", 0, 1, new Proposal(List.of(), null, ready("PLAYER_ACTION")))));
        assertThat(before.qualification()).isNull();
    }

    @Test
    void allEvidenceMustBeValidEvenWhenOneReferenceIsARealCurrentExperience() {
        Context context = context(1, Map.of("e1", currentExperience("e1", 1)));
        Proposal proposal = new Proposal(List.of(learning(BREATHING, "缓步可维持吐纳", "尚不稳定", "e1", "missing")),
                null, none());

        assertRejected(Failure.INVALID_EVIDENCE,
                () -> prototype.propose(initial(), context, request("request-1", 0, 1, proposal)));
    }

    @Test
    void learningEvidenceMustActuallyReferenceTheKnownAbility() {
        Evidence unrelated = experience("e1", 1, Acceptance.ACCEPTED_THIS_TURN, Set.of(WALKING));
        Context context = context(1, Map.of("e1", unrelated));

        assertRejected(Failure.INVALID_EVIDENCE,
                () -> prototype.propose(initial(), context, request("request-1", 0, 1, practice("e1"))));
    }

    @Test
    void learningCannotCreateAnUnknownAbilityThroughTheGrowthPath() {
        Proposal proposal = new Proposal(List.of(learning("ability:unknown", "突然掌握", "尚不稳定", "e1")),
                null, none());

        assertRejected(Failure.INVALID_PROPOSAL, () -> prototype.propose(initial(),
                context(1, Map.of("e1", currentExperience("e1", 1))), request("request-1", 0, 1, proposal)));
    }

    @ParameterizedTest
    @EnumSource(value = Readiness.class, names = {"NONE", "NOT_READY"})
    void omissionOrNotReadyCannotWithdrawExistingQualification(Readiness conclusion) {
        State qualified = qualified();
        Proposal proposal = new Proposal(List.of(), null,
                new QualificationProposal(conclusion, null, "本轮没有重新形成资格判断", Set.of()));

        State result = prototype.propose(qualified, context(2, Map.of()),
                request("request-2", 1, 2, proposal)).candidate();

        assertThat(result.qualification()).isEqualTo(qualified.qualification());
        assertThat(result.hasUnblockedQualification()).isTrue();
    }

    @Test
    void readinessCanAddEvidenceButKeepsOriginalReasonAndGrantedTurn() {
        State qualified = qualified();
        QualificationProposal reiterated = new QualificationProposal(Readiness.READY, NEXT,
                "换一种说法再次认为准备充分", Set.of("e1", "e2"));
        Context context = context(2, Map.of("e1", committedExperience("e1", 1),
                "e2", currentExperience("e2", 2)));

        State result = prototype.propose(qualified, context,
                request("request-2", 1, 2, new Proposal(List.of(), null, reiterated))).candidate();

        assertThat(result.qualification().reason()).isEqualTo(qualified.qualification().reason());
        assertThat(result.qualification().grantedTurn()).isEqualTo(1);
        assertThat(result.qualification().evidenceRefs()).containsExactlyInAnyOrder("e1", "e2");
        assertThat(result.position()).isEqualTo(CURRENT);
    }

    @Test
    void injuryTemporarilyBlocksExecutionAndRecoveryDoesNotRegrantQualification() {
        State qualified = qualified();
        Evidence injury = event("injury", SAVE, 2, CURRENT, EvidenceKind.CONSTRAINT, Acceptance.ACCEPTED_THIS_TURN);
        Context injuredContext = new Context(SAVE, 2, CURRENT, NEXT, Set.of(BREATHING, WALKING),
                Map.of("injury", injury), Set.of("injury"));

        State blocked = prototype.propose(qualified, injuredContext,
                request("injury-turn", 1, 2, emptyProposal())).candidate();
        State recovered = prototype.propose(blocked, context(3, Map.of()),
                request("recovery-turn", 2, 3, emptyProposal())).candidate();

        assertThat(blocked.qualification()).isEqualTo(qualified.qualification());
        assertThat(blocked.hasUnblockedQualification()).isFalse();
        assertThat(blocked.blockerEventRefs()).containsExactly("injury");
        assertThat(recovered.hasUnblockedQualification()).isTrue();
        assertThat(recovered.qualification()).isEqualTo(qualified.qualification());
        assertThat(recovered.qualification().grantedTurn()).isEqualTo(1);
        assertThat(recovered.blockerEventRefs()).isEmpty();
    }

    @Test
    void aPlanCannotBecomeAnExecutionBlocker() {
        State before = qualified();
        Evidence plannedInjury = event("injury", SAVE, 2, CURRENT, EvidenceKind.PLAN, Acceptance.ACCEPTED_THIS_TURN);
        Context context = new Context(SAVE, 2, CURRENT, NEXT, Set.of(BREATHING),
                Map.of("injury", plannedInjury), Set.of("injury"));

        assertRejected(Failure.INVALID_EVIDENCE,
                () -> prototype.propose(before, context, request("request-2", 1, 2, emptyProposal())));
        assertThat(before.hasUnblockedQualification()).isTrue();
        assertThat(before.blockerEventRefs()).isEmpty();
    }

    @Test
    void nonAdjacentTargetIsRejectedWithoutAdvancingCurrentPosition() {
        State before = initial();
        Proposal proposal = new Proposal(List.of(), null,
                new QualificationProposal(Readiness.READY, LATER, "试图越过合法后继", Set.of("e1")));

        assertRejected(Failure.INVALID_TARGET, () -> prototype.propose(before,
                context(1, Map.of("e1", currentExperience("e1", 1))), request("request-1", 0, 1, proposal)));
        assertThat(before.position()).isEqualTo(CURRENT);
        assertThat(before.qualification()).isNull();
    }

    @Test
    void trackEndCannotManufactureASuccessorQualification() {
        Context context = new Context(SAVE, 1, CURRENT, null, Set.of(BREATHING),
                Map.of("e1", currentExperience("e1", 1)), Set.of());

        assertRejected(Failure.INVALID_TARGET, () -> prototype.propose(initial(), context,
                request("request-1", 0, 1, new Proposal(List.of(), null, ready("e1")))));
    }

    @Test
    void selfSuccessorCannotCreateQualification() {
        Context context = new Context(SAVE, 1, CURRENT, CURRENT, Set.of(BREATHING),
                Map.of("e1", currentExperience("e1", 1)), Set.of());
        Proposal proposal = new Proposal(List.of(), null,
                new QualificationProposal(Readiness.READY, CURRENT, "没有前进", Set.of("e1")));

        assertRejected(Failure.INVALID_TARGET,
                () -> prototype.propose(initial(), context, request("request-1", 0, 1, proposal)));
    }

    @Test
    void qualificationAtRealmBoundaryStillDoesNotExecuteBreakthrough() {
        Position perfect = new Position("qi", "perfect");
        Position nextRealm = new Position("foundation", "early");
        State before = new State(SAVE, 0, 0, perfect, Map.of(), null, null, Set.of(), null);
        Context context = new Context(SAVE, 1, perfect, nextRealm, Set.of(BREATHING),
                Map.of("e1", event("e1", SAVE, 1, perfect, EvidenceKind.EXPERIENCE, Acceptance.ACCEPTED_THIS_TURN)),
                Set.of());
        Proposal proposal = new Proposal(List.of(), null,
                new QualificationProposal(Readiness.READY, nextRealm, "已具备准备依据，尚未尝试", Set.of("e1")));

        State result = prototype.propose(before, context, request("request-1", 0, 1, proposal)).candidate();

        assertThat(result.qualification().target()).isEqualTo(nextRealm);
        assertThat(result.position()).isEqualTo(perfect);
        assertThat(result.hasUnblockedQualification()).isTrue();
    }

    @Test
    void rejectionAfterValidLearningDoesNotPartiallyMutatePriorStateOrReceipt() {
        State before = prototype.propose(initial(), context(1, Map.of("e1", currentExperience("e1", 1))),
                request("request-1", 0, 1, practice("e1"))).candidate();
        Observation originalLearning = before.learning().get(BREATHING);
        Proposal invalid = new Proposal(List.of(learning(BREATHING, "练习后更加稳定", "不能快步", "e2")),
                observation("再次积累", "暂无额外突破依据", "missing"), none());

        assertRejected(Failure.INVALID_EVIDENCE, () -> prototype.propose(before,
                context(2, Map.of("e2", currentExperience("e2", 2))), request("request-2", 1, 2, invalid)));

        assertThat(before.learning().get(BREATHING)).isSameAs(originalLearning);
        assertThat(before.learning().get(BREATHING).evidenceRefs()).containsExactly("e1");
        assertThat(before.accumulation().evidenceRefs()).containsExactly("e1");
        assertThat(before.revision()).isEqualTo(1);
        assertThat(before.lastTurn()).isEqualTo(1);
        assertThat(before.lastReceipt().request().key()).isEqualTo("request-1");
    }

    @Test
    void requestCannotCrossSaveBoundaryOrSkipAConfirmedTurn() {
        Context context = context(1, Map.of());
        Request anotherSave = new Request("request-1", "other-save", 0, 1, emptyProposal());

        assertRejected(Failure.SCOPE, () -> prototype.propose(initial(), context, anotherSave));
        assertRejected(Failure.SCOPE, () -> prototype.propose(initial(), context(2, Map.of()),
                request("request-2", 0, 2, emptyProposal())));
    }

    @Test
    void observationsProposalsAndCandidatesDefensivelyCopyCollections() {
        Set<String> mutableRefs = new HashSet<>(Set.of("e1"));
        Observation observation = new Observation("缓步可维持吐纳", "快步仍会散乱", mutableRefs);
        List<Learning> mutableLearning = new ArrayList<>(List.of(new Learning(BREATHING, observation)));
        Proposal proposal = new Proposal(mutableLearning, null, none());
        Map<String, Evidence> mutableEvents = new HashMap<>(Map.of("e1", currentExperience("e1", 1)));
        Context context = context(1, mutableEvents);
        mutableRefs.add("forged");
        mutableLearning.clear();
        mutableEvents.clear();

        State result = prototype.propose(initial(), context, request("request-1", 0, 1, proposal)).candidate();

        assertThat(result.learning().get(BREATHING).evidenceRefs()).containsExactly("e1");
        assertThatThrownBy(() -> result.learning().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.learning().get(BREATHING).evidenceRefs().add("forged"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> proposal.learning().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private State qualified() {
        return prototype.propose(initial(), context(1, Map.of("e1", currentExperience("e1", 1))),
                request("request-1", 0, 1, new Proposal(List.of(), null, ready("e1")))).candidate();
    }

    private static State initial() {
        return emptyState(0, 0);
    }

    private static State emptyState(long revision, int lastTurn) {
        return new State(SAVE, revision, lastTurn, CURRENT, Map.of(), null, null, Set.of(), null);
    }

    private static Context context(int turn, Map<String, Evidence> events) {
        return new Context(SAVE, turn, CURRENT, NEXT, Set.of(BREATHING, WALKING), events, Set.of());
    }

    private static Request request(String key, long revision, int turn, Proposal proposal) {
        return new Request(key, SAVE, revision, turn, proposal);
    }

    private static Evidence currentExperience(String id, int turn) {
        return experience(id, turn, Acceptance.ACCEPTED_THIS_TURN, Set.of(BREATHING));
    }

    private static Evidence committedExperience(String id, int turn) {
        return experience(id, turn, Acceptance.COMMITTED, Set.of(BREATHING));
    }

    private static Evidence experience(String id, int turn, Acceptance acceptance, Set<String> abilities) {
        return new Evidence(id, SAVE, turn, CURRENT, EvidenceKind.EXPERIENCE, acceptance, abilities);
    }

    private static Evidence event(String id, String save, int turn, Position position,
                                  EvidenceKind kind, Acceptance acceptance) {
        return new Evidence(id, save, turn, position, kind, acceptance, Set.of(BREATHING));
    }

    private static Proposal practice(String eventRef) {
        return new Proposal(List.of(learning(BREATHING, "缓步可维持吐纳", "快步仍会散乱", eventRef)),
                observation("实际练习形成积累", "尚未足以推进阶段", eventRef), none());
    }

    private static Proposal emptyProposal() {
        return new Proposal(List.of(), null, none());
    }

    private static Learning learning(String ability, String achieved, String limitations, String... evidence) {
        return new Learning(ability, observation(achieved, limitations, evidence));
    }

    private static Observation observation(String achieved, String limitations, String... evidence) {
        return new Observation(achieved, limitations, Set.of(evidence));
    }

    private static QualificationProposal none() {
        return new QualificationProposal(Readiness.NONE, null, "本轮未提出资格变化", Set.of());
    }

    private static QualificationProposal ready(String... evidence) {
        return new QualificationProposal(Readiness.READY, NEXT, "已接受的修行经历支持阶段准备", Set.of(evidence));
    }

    private static void assertRejected(Failure expected, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(Rejected.class,
                rejected -> assertThat(rejected.failure()).isEqualTo(expected));
    }
}
