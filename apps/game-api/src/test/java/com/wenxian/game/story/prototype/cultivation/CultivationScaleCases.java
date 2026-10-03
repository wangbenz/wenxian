package com.wenxian.game.story.prototype.cultivation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.ai.NarrativeWriterAi.NarrativeDraft;
import com.wenxian.game.story.worldpackage.domain.WorldContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthPrototype.*;
import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthReplay.*;

/**
 * 从已审阅资源装配四份独立合成前态，不把评审标签交给导演，也不声称这些记录来自实际玩家或模型。
 *
 * <p>世界修订与合法目标仍由官方 Bundle 得到。历史学习、积累来自显式资源，不从经历中文推导；
 * 通过原型校验引用后建立两个完整的合成历史记录。这里只校验结构和身份，不能证明资格理由充分。</p>
 */
public final class CultivationScaleCases {
    public static final String RESOURCE = "story/cultivation-stage-scale-cases.json";
    private static final String HISTORY_ACTION = "独自按已有行气法内修，记录实际观察，完成后收功。";
    private static final String HISTORY_TIME = "一次已完成的内修，具体时长未记录";
    private final String syntheticNote;
    private final Scenario scenario;
    private final String playerAction;
    private final List<ScaleCase> cases;

    /** id 与 reviewHints 只供本地审阅；发给模型时只取 before，经 directorInput 构造正式实验输入。 */
    public record ScaleCase(String id, Checkpoint before, List<String> reviewHints) {
        public ScaleCase { reviewHints = List.copyOf(reviewHints); }
    }

    private record Document(String syntheticNote, Scenario commonScenario, String playerAction, List<CaseSeed> cases) {}
    private record CaseSeed(String id, List<ExperienceRecord> experiences, ObservationDraft learning,
                            ObservationDraft accumulation, QualificationSeed qualification,
                            List<String> reviewHints, GrowthSeed firstTurnGrowth) {}
    private record GrowthSeed(ObservationDraft learning, ObservationDraft accumulation) {}
    private record QualificationSeed(String reason, List<String> evidenceRefs, int grantedTurn) {}

    private CultivationScaleCases(String syntheticNote, Scenario scenario, String playerAction, List<ScaleCase> cases) {
        this.syntheticNote = syntheticNote;
        this.scenario = scenario;
        this.playerAction = playerAction;
        this.cases = List.copyOf(cases);
    }

    public String syntheticNote() { return syntheticNote; }
    public Scenario scenario() { return scenario; }
    public String playerAction() { return playerAction; }
    public List<ScaleCase> cases() { return cases; }

    public static CultivationScaleCases load(ObjectMapper mapper) {
        try (var input = CultivationScaleCases.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            require(input != null, "missing scale cases resource");
            Document document = mapper.readValue(input, Document.class);
            requireText(document.syntheticNote(), "synthetic note");
            requireText(document.playerAction(), "common player action");
            require(document.commonScenario() != null && document.cases() != null && document.cases().size() == 4,
                    "four cases and an explicit common scenario are required");
            require(document.cases().stream().allMatch(seed -> seed != null && seed.id() != null)
                            && document.cases().stream().map(CaseSeed::id).collect(java.util.stream.Collectors.toSet())
                            .equals(Set.of("C01", "C02", "C03", "C04")), "neutral case ids must be unique C01-C04");
            CultivationGrowthReplay replay = new CultivationGrowthReplay(mapper, document.commonScenario());
            Checkpoint initial = replay.initial();
            // 只取统一包身份和位置，不把旧实验“未研习抄本”的初始观察带入新的四个前态。
            State emptyHistory = new State(SAVE, 0, 0, initial.growth().position(), Map.of(), null, null, Set.of(), null);
            Checkpoint blank = new Checkpoint(initial.worldContentHash(), emptyHistory, List.of(), List.of());
            Map<String, Object> source = replay.directorInput(blank, document.playerAction());
            Position next = (Position) source.get("legalNextPosition");
            WorldContext worldContext = (WorldContext) source.get("worldContext");
            List<ScaleCase> cases = document.cases().stream().map(seed -> new ScaleCase(seed.id(),
                    assemble(seed, blank, next, worldContext), checkedHints(seed.reviewHints()))).toList();
            return new CultivationScaleCases(document.syntheticNote(), document.commonScenario(), document.playerAction(), cases);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load reviewed synthetic scale cases", error);
        }
    }

    private static Checkpoint assemble(CaseSeed seed, Checkpoint blank, Position next, WorldContext worldContext) {
        require(seed.experiences() != null && seed.experiences().size() == 2 && seed.firstTurnGrowth() != null,
                "each case needs two historical experiences and explicit first-turn growth");
        State state = blank.growth();
        List<ExperienceRecord> history = new ArrayList<>();
        List<TurnRecord> turns = new ArrayList<>();
        CultivationGrowthPrototype prototype = new CultivationGrowthPrototype();
        for (int turn = 1; turn <= 2; turn++) {
            ExperienceRecord event = seed.experiences().get(turn - 1);
            require(event != null && event.turn() == turn && ("experience:scale:" + turn).equals(event.id()),
                    "historical ids and turns must be identical across all cases");
            requireText(event.statement(), "historical experience");
            require(Set.of(ABILITY).containsAll(event.abilityRefs()), "unknown ability in synthetic experience");
            GrowthSeed growth = turn == 1 ? seed.firstTurnGrowth() : new GrowthSeed(seed.learning(), seed.accumulation());
            require(growth.learning() != null, "explicit historical learning is required");
            Observation learning = observation(growth.learning());
            Observation accumulation = growth.accumulation() == null ? null : observation(growth.accumulation());
            QualificationSeed qualification = turn == 2 ? seed.qualification() : null;
            if (qualification != null) require(qualification.grantedTurn() == turn, "qualification grant turn mismatch");
            QualificationProposal offered = qualification == null
                    ? new QualificationProposal(Readiness.NONE, null, "合成前态记录：本段未声明新的阶段资格", Set.of())
                    : new QualificationProposal(Readiness.READY, next, qualification.reason(), refs(qualification.evidenceRefs()));
            Proposal proposal = new Proposal(List.of(new Learning(ABILITY, learning)), accumulation, offered);
            Map<String, Evidence> events = new LinkedHashMap<>();
            for (ExperienceRecord old : history) events.put(old.id(), evidence(old, state.position(), Acceptance.COMMITTED));
            events.put(event.id(), evidence(event, state.position(), Acceptance.ACCEPTED_THIS_TURN));
            Context context = new Context(SAVE, turn, state.position(), next, Set.of(ABILITY), events, Set.of());
            State after = prototype.propose(state, context,
                    new Request("synthetic-scale-history-" + turn, SAVE, state.revision(), turn, proposal)).candidate();
            String localRef = "seed-" + turn;
            GrowthDraft wireGrowth = new GrowthDraft(List.of(new LearningDraft(ABILITY, learning.achieved(), learning.limitations(),
                    wireRefs(learning.evidenceRefs(), event.id(), localRef))), accumulation == null ? null
                    : new ObservationDraft(accumulation.achieved(), accumulation.limitations(), wireRefs(accumulation.evidenceRefs(), event.id(), localRef)),
                    new QualificationDraft(offered.conclusion(), offered.target(), offered.reason(),
                            wireRefs(offered.evidenceRefs(), event.id(), localRef)));
            // SUCCEEDED 仅表示资源明确给出的内修过程完成，不表示达到下一阶段的充分条件。
            DirectorDraft proposed = new DirectorDraft(Resolution.SUCCEEDED, event.statement(), HISTORY_TIME,
                    List.of(new ExperienceDraft(localRef, event.statement(), event.abilityRefs().stream().sorted().toList())),
                    wireGrowth, List.of(event.statement()), "合成历史记录：该次内修已经收功，没有执行晋升。");
            AcceptedOutcome accepted = new AcceptedOutcome(proposed.resolution(), event.statement(), HISTORY_TIME,
                    List.of(event), proposed.sceneBeats(), proposed.stoppingPoint());
            NarrativeDraft prose = new NarrativeDraft("合成内修记录" + turn, event.statement(), List.of(), event.statement());
            turns.add(new TurnRecord(turn, HISTORY_ACTION, proposed, accepted, state, after, prose, worldContext));
            history.add(event);
            state = after;
        }
        require(state.revision() == 2 && state.lastTurn() == 2, "synthetic cases must share the same metadata");
        return new Checkpoint(blank.worldContentHash(), state, history, turns);
    }

    private static Evidence evidence(ExperienceRecord event, Position position, Acceptance acceptance) {
        return new Evidence(event.id(), SAVE, event.turn(), position, EvidenceKind.EXPERIENCE, acceptance, event.abilityRefs());
    }

    private static Observation observation(ObservationDraft value) {
        return new Observation(value.achieved(), value.limitations(), refs(value.evidenceRefs()));
    }

    private static Set<String> refs(List<String> values) {
        require(values != null && values.stream().allMatch(value -> value != null && !value.isBlank()), "invalid synthetic evidence refs");
        return Set.copyOf(values);
    }

    private static List<String> wireRefs(Set<String> refs, String currentId, String localRef) {
        return refs.stream().sorted().map(ref -> ref.equals(currentId) ? localRef : ref).toList();
    }

    private static List<String> checkedHints(List<String> hints) {
        require(hints != null && hints.stream().allMatch(value -> value != null && !value.isBlank()), "invalid review-only hints");
        return List.copyOf(hints);
    }

    private static void requireText(String value, String field) { require(value != null && !value.isBlank(), "missing " + field); }
    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
