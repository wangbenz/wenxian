package com.wenxian.game.story.prototype.cultivation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.ai.NarrativeWriterAi.NarrativeDraft;
import com.wenxian.game.story.worldpackage.application.WorldContextSelector;
import com.wenxian.game.story.worldpackage.application.WorldPackageIngestionPipeline;
import com.wenxian.game.story.worldpackage.domain.WorldContext;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthPrototype.*;

/**
 * 合成角色的实验回放宿主：加载官方包、接受结构合法的经历候选，再归并成长并交给正文。
 * 只复用两阶段职责、世界摄取/选择器和成长原型，不冒充正式回合编译器或数据库事务。
 * 中文经历的合理性仍待人工审读；正文完整后才返回新检查点，任何失败都不修改输入。
 */
public final class CultivationGrowthReplay {
    static final String SAVE = "synthetic-growth-replay";
    static final String ABILITY = "ability:replay:breathing";
    static final String RACE = "race:xuanxu:human";
    static final String REGION = "region:xuanxu:qingqi";
    private final ObjectMapper mapper;
    private final ResolvedRevision world;
    private final Position current;
    private final Position next;
    private final Scenario scenario;

    /** 合成场景由调用方显式冻结；只替换背景数据，不更换能力身份、世界轨道或成长规则。 */
    public record Scenario(String character, String abilityName, String abilityInitialStatus,
                           String location, String people, String scope) {
        public Scenario {
            bounded(character, 2000);
            bounded(abilityName, 200);
            bounded(abilityInitialStatus, 1000);
            bounded(location, 1000);
            bounded(people, 1000);
            bounded(scope, 2000);
        }
    }

    private static final Scenario DEFAULT_SCENARIO = new Scenario(
            "沈知微，人族散修，炼气初期，当前只有吐纳诀抄本和普通随身物品。希望学到能实际用上的本领。",
            "吐纳诀", "持有抄本，不代表已经学会",
            "东陆青岐州一处民间讲习院的练功廊与相连院落，本批行动限于此处。",
            "讲习者许先生愿意回答当前练习问题，双方没有师徒关系或必须履行的新约定。",
            "合成非危险研习样本；可以有失败和无进展。重大新代价/决定处停止。本批只观察准备，不执行实际境界晋升。");

    public CultivationGrowthReplay(ObjectMapper mapper) {
        this(mapper, DEFAULT_SCENARIO);
    }

    /** 显式场景同时用于导演输入与 prepare 的交接，旧构造仍原样使用默认回放背景。 */
    public CultivationGrowthReplay(ObjectMapper mapper, Scenario scenario) {
        this.mapper = mapper;
        this.scenario = java.util.Objects.requireNonNull(scenario, "synthetic scenario");
        // 仅实验 mapper 忽略由固定 getter 派生的只读字段，其他未知字段仍明确拒绝。
        mapper.addMixIn(WorldContext.class, WorldContextReadback.class);
        var candidate = new WorldPackageIngestionPipeline(mapper).inspectDirectory(
                Path.of("src/main/resources/world-packages/official/XUANXU/2"));
        var identity = candidate.compiled().identity();
        // 仅供纯选择器使用的合成治理头，不发布世界包、不读取或写入注册表。
        var packageRow = new PackageRow("synthetic-package", identity.publisherNamespace(), identity.packageId(),
                null, "OFFICIAL", "PUBLIC", "ACTIVE", identity.revision());
        var revision = new RevisionRow("synthetic-revision", packageRow.id(), identity.revision(),
                identity.formatVersion(), identity.runtimeContract(), candidate.contentHash(), candidate.compiledHash(),
                candidate.compilerVersion(), "PUBLISHED", "[]");
        world = new ResolvedRevision(packageRow, revision, candidate.compiled());
        String trackRef = world.compiled().creationProfile().progressionTrackByIdentity().get(RACE);
        var system = world.compiled().progressionSystemsById().values().stream()
                .filter(value -> value.tracksById().containsKey(trackRef)).findFirst().orElseThrow();
        var track = system.tracksById().get(trackRef);
        current = new Position(track.startingStageRef(), track.startingStepRef());
        String successor = system.stepsById().get(current.stepRef()).nextStepId();
        check(successor != null, "fixture requires an adjacent step");
        next = new Position(current.stageRef(), successor);
    }

    /** 测试专用类型化导演，无工具、ChatMemory、数据库或隐式模型回退。 */
    public interface DirectorAi {
        @UserMessage("{{context}}")
        Result<DirectorDraft> direct(@V("context") String context);
    }

    public enum Resolution { SUCCEEDED, PARTIAL, FAILED }
    @JsonIgnoreProperties(value = "trustLevel", allowGetters = true)
    private abstract static class WorldContextReadback { }
    public record ExperienceDraft(String localRef, String statement, List<String> abilityRefs) { }
    public record ObservationDraft(String achieved, String limitations, List<String> evidenceRefs) { }
    public record LearningDraft(String abilityRef, String achieved, String limitations, List<String> evidenceRefs) { }
    public record QualificationDraft(Readiness conclusion, Position target, String reason, List<String> evidenceRefs) { }
    public record GrowthDraft(List<LearningDraft> learning, ObservationDraft accumulation,
                              QualificationDraft qualification) { }

    /** 模型只提本轮过程与成长依据；命卷、回合、阶段、正式事件 ID 和接受状态由宿主给出。 */
    public record DirectorDraft(Resolution resolution, String outcomeSummary, String elapsedTime,
                                List<ExperienceDraft> experiences, GrowthDraft growth,
                                List<String> sceneBeats, String stoppingPoint) { }

    public record ExperienceRecord(String id, int turn, String statement, Set<String> abilityRefs) {
        public ExperienceRecord { abilityRefs = Set.copyOf(abilityRefs); }
    }

    public record AcceptedOutcome(Resolution resolution, String summary, String elapsedTime,
                                  List<ExperienceRecord> experiences, List<String> sceneBeats, String stoppingPoint) { }
    public record TurnRecord(int turn, String playerAction, DirectorDraft proposed, AcceptedOutcome accepted,
                             State beforeGrowth, State afterGrowth, NarrativeDraft prose, WorldContext worldContext) { }

    /** 一个文件内的实验检查点；保留全部本批六轮证据，既非正式命卷合同，也不证明生产持久化。 */
    public record Checkpoint(String worldContentHash, State growth, List<ExperienceRecord> experiences,
                             List<TurnRecord> turns) {
        public Checkpoint {
            experiences = List.copyOf(experiences);
            turns = List.copyOf(turns);
        }
    }

    public Checkpoint initial() {
        Observation untouched = new Observation("持有吐纳诀抄本，尚未实际研习", "尚不能确认会任何用法", Set.of());
        State state = new State(SAVE, 0, 0, current, Map.of(ABILITY, untouched), null, null, Set.of(), null);
        return new Checkpoint(world.revision().contentHash(), state, List.of(), List.of());
    }

    /** 本地预览入口：只导出首轮业务输入和实验提示，不构造模型客户端、不读取凭证。 */
    public static void main(String[] args) throws Exception {
        check(args.length == 1, "expected an existing private preview directory");
        Path root = Path.of(args[0]).toRealPath();
        check(!root.startsWith(Path.of("../..").toRealPath()), "preview must stay outside repository");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CultivationGrowthReplay replay = new CultivationGrowthReplay(mapper);
        String action = Files.readString(root.resolve("next-action.txt")).strip();
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("destination", "https://api.deepseek.com/chat/completions");
        preview.put("model", "deepseek-v4-flash");
        preview.put("maximumSupplierCalls", 18);
        preview.put("note", "本预览包含业务数据和实验Prompt；LangChain4j另附类型化JSON输出要求。没有凭证、玩家记录或数据库数据。");
        preview.put("directorPrompt", Files.readString(Path.of("src/test/resources/prompts/cultivation-replay-director.txt")));
        preview.put("writerPrompt", Files.readString(Path.of("src/test/resources/prompts/cultivation-replay-writer.txt")));
        preview.put("firstDirectorInput", replay.directorInput(replay.initial(), action));
        Files.writeString(root.resolve("outbound-preview.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(preview),
                StandardOpenOption.CREATE_NEW);
        System.out.println("Local request preview written; no model client or credential loaded.");
    }

    public Map<String, Object> directorInput(Checkpoint before, String action) {
        validateCheckpoint(before);
        check(action != null && !action.isBlank() && action.length() <= 2000, "invalid player action");
        Map<String, Object> input = commonInput(before, action);
        input.put("legalNextPosition", next);
        input.put("acceptedExperiences", before.experiences());
        return input;
    }

    /** 只校验与归并本轮候选；真实入口控制一次结构修复，本方法不调用模型。 */
    public Prepared prepare(Checkpoint before, String action, DirectorDraft draft) {
        Map<String, Object> input = directorInput(before, action);
        check(draft != null && draft.resolution() != null && draft.growth() != null, "missing director groups");
        bounded(draft.outcomeSummary(), 1400);
        bounded(draft.elapsedTime(), 200);
        bounded(draft.stoppingPoint(), 500);
        check(draft.sceneBeats() != null && !draft.sceneBeats().isEmpty() && draft.sceneBeats().size() <= 10,
                "invalid scene beats");
        draft.sceneBeats().forEach(value -> bounded(value, 500));
        check(draft.experiences() != null && draft.experiences().size() <= 8, "invalid experience group");
        check(draft.growth().learning() != null && draft.growth().qualification() != null, "missing growth groups");
        int turn = before.growth().lastTurn() + 1;
        Set<String> historicalIds = before.experiences().stream().map(ExperienceRecord::id).collect(Collectors.toSet());
        Map<String, String> localIds = new LinkedHashMap<>();
        List<ExperienceRecord> proposedEvents = new ArrayList<>();
        for (ExperienceDraft event : draft.experiences()) {
            check(event != null && event.localRef() != null && event.localRef().matches("[a-zA-Z][a-zA-Z0-9:_-]{0,95}")
                            && !historicalIds.contains(event.localRef()),
                    "invalid local event reference");
            bounded(event.statement(), 1200);
            Set<String> abilities = requiredRefs(event.abilityRefs());
            check(Set.of(ABILITY).containsAll(abilities), "experience references unknown ability");
            String id = "experience:replay:" + turn + ":" + (proposedEvents.size() + 1);
            check(localIds.putIfAbsent(event.localRef(), id) == null, "duplicate local event reference");
            proposedEvents.add(new ExperienceRecord(id, turn, event.statement(), abilities));
        }
        Map<String, Evidence> directory = new LinkedHashMap<>();
        before.experiences().forEach(event -> directory.put(event.id(), evidence(event, Acceptance.COMMITTED)));
        proposedEvents.forEach(event -> directory.put(event.id(), evidence(event, Acceptance.ACCEPTED_THIS_TURN)));
        Function<List<String>, Set<String>> resolve = refs -> requiredRefs(refs).stream()
                .map(ref -> localIds.getOrDefault(ref, ref)).collect(Collectors.toUnmodifiableSet());
        Set<String> offeredAbilities = new java.util.HashSet<>();
        List<Learning> learning = draft.growth().learning().stream().map(value -> {
            check(value != null, "null learning");
            bounded(value.achieved(), 1200);
            bounded(value.limitations(), 800);
            Learning change = new Learning(value.abilityRef(), new Observation(value.achieved(), value.limitations(),
                    resolve.apply(value.evidenceRefs())));
            check(offeredAbilities.add(change.abilityRef()), "duplicate learning ability");
            return change;
        // 完整照回已有观察是保持原状，不是假装本轮新增成长；任何变化仍须经过当前经历校验。
        }).filter(value -> !value.observation().equals(before.growth().learning().get(value.abilityRef()))).toList();
        ObservationDraft offeredAccumulation = draft.growth().accumulation();
        Observation accumulation = offeredAccumulation == null ? null : new Observation(
                bounded(offeredAccumulation.achieved(), 1600), bounded(offeredAccumulation.limitations(), 800),
                resolve.apply(offeredAccumulation.evidenceRefs()));
        if (accumulation != null && accumulation.equals(before.growth().accumulation())) accumulation = null;
        QualificationDraft offered = draft.growth().qualification();
        Proposal proposal = new Proposal(learning, accumulation, new QualificationProposal(offered.conclusion(),
                offered.target(), bounded(offered.reason(), 1200), resolve.apply(offered.evidenceRefs())));
        var context = new Context(SAVE, turn, current, next, Set.of(ABILITY), directory, Set.of());
        State after = new CultivationGrowthPrototype().propose(before.growth(), context,
                new Request("replay-turn-" + turn, SAVE, before.growth().revision(), turn, proposal)).candidate();
        AcceptedOutcome accepted = new AcceptedOutcome(draft.resolution(), draft.outcomeSummary(), draft.elapsedTime(),
                List.copyOf(proposedEvents), List.copyOf(draft.sceneBeats()), draft.stoppingPoint());
        Map<String, Object> writerInput = new LinkedHashMap<>(input);
        writerInput.remove("legalNextPosition");
        writerInput.remove("acceptedExperiences");
        writerInput.put("acceptedOutcome", accepted);
        writerInput.put("afterGrowth", growthView(after));
        return new Prepared(before, action, draft, accepted, after, (WorldContext) input.get("worldContext"),
                Map.copyOf(writerInput));
    }

    record Prepared(Checkpoint before, String action, DirectorDraft proposed, AcceptedOutcome outcome,
                    State after, WorldContext worldContext, Map<String, Object> writerInput) { }

    public Checkpoint complete(Prepared prepared, NarrativeDraft prose) {
        check(prose != null, "missing prose");
        bounded(prose.title(), 120);
        bounded(prose.narrative(), 8000);
        bounded(prose.summary(), 2000);
        check(prose.choices() != null && prose.choices().size() <= 4, "invalid choices");
        prose.choices().forEach(choice -> {
            check(choice != null, "null choice");
            bounded(choice.title(), 120);
            bounded(choice.description(), 600);
        });
        List<ExperienceRecord> allEvents = new ArrayList<>(prepared.before().experiences());
        allEvents.addAll(prepared.outcome().experiences());
        List<TurnRecord> turns = new ArrayList<>(prepared.before().turns());
        turns.add(new TurnRecord(prepared.after().lastTurn(), prepared.action(), prepared.proposed(), prepared.outcome(),
                prepared.before().growth(), prepared.after(), prose, prepared.worldContext()));
        return new Checkpoint(prepared.before().worldContentHash(), prepared.after(), allEvents, turns);
    }

    private Map<String, Object> commonInput(Checkpoint before, String action) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("scenario", Map.of(
                "character", scenario.character(),
                "ability", Map.of("id", ABILITY, "name", scenario.abilityName(), "initialStatus", scenario.abilityInitialStatus()),
                "location", scenario.location(), "people", scenario.people(), "scope", scenario.scope()));
        input.put("playerAction", action);
        input.put("turn", before.growth().lastTurn() + 1);
        input.put("beforeGrowth", growthView(before.growth()));
        input.put("recentTurns", before.turns().stream().map(turn -> Map.of(
                "turn", turn.turn(), "playerAction", turn.playerAction(), "acceptedOutcome", turn.accepted(),
                "narrativeForContinuityOnly", turn.prose().narrative())).toList());
        input.put("worldContext", new WorldContextSelector(mapper).select(world, new WorldContextSelector.Query(
                RACE, REGION, current.stageRef(), Set.of("修行", "功法"), Set.of(), action, List.of())));
        return input;
    }

    private Map<String, Object> growthView(State state) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("position", state.position());
        view.put("realmName", "炼气初期");
        view.put("learning", state.learning());
        view.put("accumulation", state.accumulation());
        view.put("qualification", state.qualification());
        view.put("blockerEventRefs", state.blockerEventRefs());
        return view;
    }

    private Evidence evidence(ExperienceRecord event, Acceptance acceptance) {
        return new Evidence(event.id(), SAVE, event.turn(), current, EvidenceKind.EXPERIENCE,
                acceptance, event.abilityRefs());
    }

    private void validateCheckpoint(Checkpoint before) {
        check(before != null && before.worldContentHash().equals(world.revision().contentHash()), "world hash mismatch");
        check(before.growth().saveId().equals(SAVE) && before.growth().position().equals(current)
                && before.growth().lastTurn() == before.turns().size() && before.turns().size() < 6,
                "invalid checkpoint or six turn limit reached");
        Set<String> ids = new java.util.HashSet<>();
        before.experiences().forEach(event -> check(event.turn() > 0 && event.turn() <= before.growth().lastTurn()
                && ids.add(event.id()), "invalid historical experience"));
    }

    private static Set<String> requiredRefs(List<String> refs) {
        check(refs != null && refs.size() <= 32 && refs.stream().allMatch(ref -> ref != null && !ref.isBlank()),
                "invalid evidence references");
        return Set.copyOf(refs);
    }

    private static String bounded(String value, int max) {
        check(value != null && !value.isBlank() && value.length() <= max, "missing or oversized text");
        return value;
    }

    /** 仅携带实验宿主自己定义的错误原因，可安全用于定向协议修复。 */
    static final class InvalidReplay extends IllegalArgumentException {
        InvalidReplay(String reason) { super(reason); }
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new InvalidReplay(reason);
    }
}
