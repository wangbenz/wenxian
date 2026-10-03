package com.wenxian.game.story.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.reducer.StoryStateReducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 记录可信回合从上下文装配到事实提交的最小诊断轨迹。
 *
 * <p>诊断是旁路观察能力，不是新的事实来源。任何诊断写入错误都会被吞掉，命卷仍以 Reducer 和
 * 事务提交结果为准。记录中只保留稳定引用和结构化变化概要，避免复制完整 Prompt 与剧情正文。</p>
 */
@Service
public class StoryTurnDiagnosticService {
    public static final String PROMPT_VERSION = "typed-directed-turn-v8";

    private final StoryTurnDiagnosticRepository repository;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public StoryTurnDiagnosticService(
            StoryTurnDiagnosticRepository repository,
            ObjectMapper mapper,
            @Value("${wenxian.story.turn-diagnostics-enabled:false}") boolean enabled) {
        this.repository = repository;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    /** 开始一条新的诊断轨迹；同一 requestId 重试时会重置未提交轨迹。 */
    public Session begin(String saveId, String requestId, int turnNumber, long expectedRevision) {
        return begin(saveId, requestId, turnNumber, expectedRevision, PROMPT_VERSION);
    }

    /** 按实际生成合同标记诊断；历史对照的默认版本不被改写成新记录版本。 */
    public Session begin(String saveId, String requestId, int turnNumber, long expectedRevision, String promptVersion) {
        Session session = new Session(this, "story-diagnostic-" + UUID.randomUUID(), saveId, requestId,
                turnNumber, expectedRevision, Instant.now(), enabled, promptVersion);
        session.persist("PREPARING", "BEGIN");
        return session;
    }

    private void save(Snapshot snapshot) {
        try {
            repository.upsert(snapshot);
        } catch (RuntimeException ignored) {
            // 诊断表缺失、短暂不可写或序列化失败都不能伤害玩家命卷。
        }
    }

    /** 一次请求在内存中的诊断会话；每个阶段覆盖保存完整快照，便于失败后直接查看。 */
    public static final class Session {
        private final StoryTurnDiagnosticService owner;
        private final String id;
        private final String saveId;
        private final String requestId;
        private final int turnNumber;
        private final long expectedRevision;
        private final Instant createdAt;
        private final boolean enabled;
        private final String promptVersion;
        private final ObjectNode contextManifest;
        private final ObjectNode actionContract;
        private final ArrayNode proposalTrace;
        private final ArrayNode reviewTrace;
        private final ObjectNode commitSummary;
        private String failureCode = "";
        private String failureDetail = "";

        private Session(StoryTurnDiagnosticService owner, String id, String saveId, String requestId,
                        int turnNumber, long expectedRevision, Instant createdAt, boolean enabled, String promptVersion) {
            this.owner = owner;
            this.id = id;
            this.saveId = saveId;
            this.requestId = requestId;
            this.turnNumber = turnNumber;
            this.expectedRevision = expectedRevision;
            this.createdAt = createdAt;
            this.enabled = enabled;
            this.promptVersion = promptVersion;
            this.contextManifest = owner.mapper.createObjectNode();
            this.actionContract = owner.mapper.createObjectNode();
            this.proposalTrace = owner.mapper.createArrayNode();
            this.reviewTrace = owner.mapper.createArrayNode();
            this.commitSummary = owner.mapper.createObjectNode();
        }

        public void context(StoryPromptContext context) {
            ObjectNode value = owner.mapper.createObjectNode();
            value.set("worldTime", owner.mapper.valueToTree(context.objective().worldTime()));
            value.put("worldClock", context.objective().worldTime().display());
            value.put("engineRuleCount", context.system().engineRules().size());
            if (context.worldContext() != null) {
                var world = context.worldContext();
                ObjectNode selection = owner.mapper.createObjectNode();
                selection.set("source", owner.mapper.valueToTree(world.source()));
                selection.put("selectorVersion", world.selectorVersion());
                selection.put("candidateCount", world.candidateCount());
                selection.put("omittedCount", world.omittedCount());
                selection.put("usedCharacters", world.usedCharacters());
                selection.put("maximumCharacters", world.maximumCharacters());
                selection.set("unavailableInputs", owner.mapper.valueToTree(world.unavailableInputs()));
                ArrayNode references = selection.putArray("selectedMaterials");
                world.materials().forEach(material -> {
                    ObjectNode reference = references.addObject();
                    reference.put("ref", material.ref());
                    reference.put("kind", material.kind());
                    reference.set("reasons", owner.mapper.valueToTree(material.reasons()));
                });
                value.set("worldContext", selection);
            }
            value.put("entityIndexPolicy", "CURRENT_ASSETS_SCENE_RECENT_AND_EXPLICIT_REFERENCES");
            value.set("entityIndex", entityRefs(context.objective().entityIndex()));
            value.put("entityDetailPolicy", "RECENT_VISIBLE_AND_EXPLICIT_REFERENCES");
            value.set("entityDetails", detailRefs(context.objective().relatedEntities()));
            value.put("inventoryPolicy", "COMPLETE_CURRENT_INVENTORY");
            value.set("currentInventory", entityRefs(context.objective().currentInventory().items()));
            value.set("historicalInventory", entityRefs(context.objective().currentInventory().historicalItems()));
            value.set("worldFacts", owner.mapper.valueToTree(context.objective().worldFacts().stream()
                    .map(fact -> owner.mapper.createObjectNode().put("id", fact.id()).put("revision", fact.revision()))
                    .toList()));
            value.set("knowledgeRefs", owner.mapper.valueToTree(context.cognition().entries().stream()
                    .map(entry -> owner.mapper.createObjectNode().put("subjectRef", entry.subjectRef())
                            .put("topicRef", entry.topicRef())).toList()));
            value.set("memoryRefs", owner.mapper.valueToTree(context.memory().entries().stream()
                    .map(memory -> owner.mapper.createObjectNode().put("id", memory.id())
                            .put("type", memory.memoryType()).put("importance", memory.importance())).toList()));
            value.put("rollingSummaryIncluded", context.narrative().rollingSummary() != null
                    && !context.narrative().rollingSummary().isBlank());
            value.put("rollingSummaryCharacters", length(context.narrative().rollingSummary()));
            value.put("previousTurn", context.narrative().previousTurn() == null
                    ? 0 : context.narrative().previousTurn().sequence());
            value.put("recentTurnCount", context.narrative().recentTurns().size());
            value.set("recentTurns", owner.mapper.valueToTree(context.narrative().recentTurns().stream()
                    .map(StoryPromptContext.PreviousTurn::sequence).toList()));
            value.put("pendingDecisionIncluded", context.objective().pendingDecision() != null);
            value.put("creationContractIncluded", context.objective().creationContract() != null
                    && !context.objective().creationContract().originSummary().isBlank());
            value.set("sourceRefs", owner.mapper.valueToTree(context.objective().sourceExcerpts().stream()
                    .map(StoryPromptContext.SourceExcerpt::ref).toList()));
            value.put("selectedChoice", context.action().selectedChoice() == null
                    ? "" : context.action().selectedChoice().id());
            if (context.action().semanticDuration() != null) {
                value.set("requestedDuration", owner.mapper.valueToTree(context.action().semanticDuration()));
            }
            contextManifest.setAll(value);
            persist("PREPARING", "CONTEXT_ASSEMBLED");
        }

        public void proposal(String source, StoryPatch patch) {
            ObjectNode value = owner.mapper.createObjectNode();
            value.put("source", source);
            value.put("narrativeCharacters", length(patch.narrative()));
            value.set("time", owner.mapper.valueToTree(patch.time()));
            value.set("outcome", owner.mapper.valueToTree(patch.outcome()));
            value.set("coreState", owner.mapper.valueToTree(patch.coreState()));
            value.set("entities", owner.mapper.valueToTree(patch.entities().stream().map(entity -> {
                ObjectNode item = owner.mapper.createObjectNode();
                item.put("operation", entity.operation() == null ? "" : entity.operation().name());
                item.put("entityId", text(entity.entityId()));
                item.put("entityType", text(entity.entityType()));
                item.put("name", preview(entity.name(), 120));
                if (entity.quantity() != null) item.set("quantity", owner.mapper.valueToTree(entity.quantity()));
                if (entity.custody() != null) item.set("custody", owner.mapper.valueToTree(entity.custody()));
                if (entity.abilityState() != null) item.put("abilityState", entity.abilityState().name());
                if (entity.lifecycle() != null) item.set("lifecycle", owner.mapper.valueToTree(entity.lifecycle()));
                if (entity.relationship() != null) item.set("relationship", owner.mapper.valueToTree(entity.relationship()));
                return item;
            }).toList()));
            value.set("worldFacts", owner.mapper.valueToTree(patch.worldFacts().stream().map(fact ->
                    owner.mapper.createObjectNode()
                            .put("operation", fact.operation() == null ? "" : fact.operation().name())
                            .put("factId", text(fact.factId()))
                            .put("statementPreview", preview(fact.statement(), 160))).toList()));
            value.set("memories", owner.mapper.valueToTree(patch.memories().stream().map(memory ->
                    owner.mapper.createObjectNode().put("type", text(memory.memoryType()))
                            .put("title", preview(memory.title(), 120))
                            .set("evidenceRefs", owner.mapper.valueToTree(memory.evidenceRefs()))).toList()));
            value.set("decisionCheckpoint", owner.mapper.valueToTree(patch.decisionCheckpoint()));
            value.set("choiceTitles", owner.mapper.valueToTree(patch.choices().stream()
                    .map(StoryPatch.ChoiceDraft::title).toList()));
            proposalTrace.add(value);
            persist("GENERATED", source);
        }

        /** 只记次数与允许降级的字段名，不复制模型原稿、私有记录或修复输入。 */
        public void candidateAttempt(int attempt, int modelCallCount, java.util.List<String> downgradedFields) {
            ObjectNode value = owner.mapper.createObjectNode();
            value.put("source", "STORY_CANDIDATE");
            value.put("attempt", attempt);
            value.put("modelCallCount", modelCallCount);
            value.set("downgradedFields", owner.mapper.valueToTree(downgradedFields));
            proposalTrace.add(value);
            persist("GENERATED", "STORY_CANDIDATE");
        }

        public void rejected(String source, RuntimeException error, boolean freshNarrative) {
            ObjectNode value = owner.mapper.createObjectNode();
            value.put("source", source);
            value.put("accepted", false);
            value.put("requiresFreshNarrative", freshNarrative);
            value.put("problem", preview(error == null ? "" : error.getMessage(), 500));
            value.put("modelCallCount", 0);
            reviewTrace.add(value);
            persist("REPAIRING", source);
        }

        public void reduced(StoryStateReducer.Applied applied) {
            ObjectNode value = acceptedSummary(applied.acceptedPatch(), applied.projection().worldTime());
            commitSummary.set("acceptedBeforeCommit", value);
            persist("REDUCED", "REDUCED");
        }

        public void committed(long revision, int committedTurn, StoryPatch accepted,
                              com.wenxian.game.story.domain.StoryWorldTime worldTimeAfter) {
            commitSummary.put("revision", revision);
            commitSummary.put("turnNumber", committedTurn);
            commitSummary.set("accepted", acceptedSummary(accepted, worldTimeAfter));
            failureCode = "";
            failureDetail = "";
            persist("COMMITTED", "COMMITTED");
        }

        public void failed(String stage, RuntimeException error) {
            failureCode = error == null ? "UNKNOWN" : error.getClass().getSimpleName();
            failureDetail = preview(error == null ? "未知错误" : error.getMessage(), 1000);
            persist("FAILED", stage == null || stage.isBlank() ? "FAILED" : stage);
        }

        private ObjectNode acceptedSummary(StoryPatch patch,
                                           com.wenxian.game.story.domain.StoryWorldTime worldTimeAfter) {
            ObjectNode value = owner.mapper.createObjectNode();
            value.set("worldTimeAfter", owner.mapper.valueToTree(worldTimeAfter));
            value.put("narrativeCharacters", length(patch.narrative()));
            value.set("time", owner.mapper.valueToTree(patch.time()));
            value.set("outcome", owner.mapper.valueToTree(patch.outcome()));
            value.set("coreState", owner.mapper.valueToTree(patch.coreState()));
            value.set("entityChanges", owner.mapper.valueToTree(patch.entities().stream().map(entity ->
                    owner.mapper.createObjectNode()
                            .put("operation", entity.operation() == null ? "" : entity.operation().name())
                            .put("entityId", text(entity.entityId()))
                            .put("entityType", text(entity.entityType()))
                            .put("name", preview(entity.name(), 120))).toList()));
            value.set("worldFactChanges", owner.mapper.valueToTree(patch.worldFacts().stream().map(fact ->
                    owner.mapper.createObjectNode()
                            .put("operation", fact.operation() == null ? "" : fact.operation().name())
                            .put("factId", text(fact.factId()))).toList()));
            value.put("knowledgeChangeCount", patch.knowledge().size());
            value.put("memoryChangeCount", patch.memories().size());
            value.put("decisionCheckpoint", patch.decisionCheckpoint() != null
                    && patch.decisionCheckpoint().required());
            return value;
        }

        private ArrayNode entityRefs(java.util.List<StoryPromptContext.EntityIndexEntry> values) {
            ArrayNode array = owner.mapper.createArrayNode();
            values.forEach(value -> array.add(owner.mapper.createObjectNode()
                    .put("id", value.id()).put("type", value.entityType()).put("visible", value.visible())));
            return array;
        }

        private ArrayNode detailRefs(java.util.List<StoryPromptContext.EntityDetail> values) {
            ArrayNode array = owner.mapper.createArrayNode();
            values.forEach(value -> array.add(owner.mapper.createObjectNode()
                    .put("id", value.id()).put("type", value.entityType()).put("visible", value.visible())));
            return array;
        }

        private void persist(String status, String stage) {
            if (!enabled) return;
            Instant updatedAt = Instant.now();
            owner.save(new Snapshot(id, saveId, requestId, turnNumber, expectedRevision, promptVersion,
                    status, stage, contextManifest.deepCopy(), actionContract.deepCopy(), proposalTrace.deepCopy(),
                    reviewTrace.deepCopy(), commitSummary.deepCopy(), failureCode, failureDetail,
                    createdAt, updatedAt));
        }

        private int length(String value) {
            return value == null ? 0 : value.length();
        }

        private String text(String value) {
            return value == null ? "" : value;
        }

        private String preview(String value, int maximum) {
            if (value == null) return "";
            String safe = value.replaceAll("(?i)sk-[a-z0-9_-]{12,}", "<REDACTED_KEY>");
            return safe.substring(0, Math.min(maximum, safe.length()));
        }
    }

    /** 数据库保存的当前诊断快照。 */
    public record Snapshot(
            String id,
            String saveId,
            String requestId,
            int turnNumber,
            long expectedRevision,
            String promptVersion,
            String status,
            String currentStage,
            JsonNode contextManifest,
            JsonNode actionContract,
            JsonNode proposalTrace,
            JsonNode reviewTrace,
            JsonNode commitSummary,
            String failureCode,
            String failureDetail,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
