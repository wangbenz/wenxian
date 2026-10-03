package com.wenxian.game.story.prototype.cultivation;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 修行成长的测试专用领域原型：分别归并学习、积累和相邻阶段资格，不执行晋升。
 * 宿主提供已核对的事件目录和临时阻碍；提案不能把计划、声明或自己的资格升级为事实。
 * 返回值是可丢弃的不可变候选，不写数据库、不消费回合，也不能判断中文领悟理由是否充分。
 * 最近回执仅用于验证相邻重试；任意历史请求恢复、事务和实际状态合同由未来宿主集成处理。
 */
final class CultivationGrowthPrototype {
    enum EvidenceKind { EXPERIENCE, CONSTRAINT, ASSERTION, PLAN, QUALIFICATION }
    enum Acceptance { COMMITTED, ACCEPTED_THIS_TURN, UNACCEPTED }
    enum Readiness { NONE, NOT_READY, READY }
    enum Failure { SCOPE, STALE, REQUEST_CONFLICT, INVALID_PROPOSAL, INVALID_EVIDENCE, INVALID_TARGET }

    record Position(String stageRef, String stepRef) {
        Position {
            stageRef = text(stageRef);
            stepRef = text(stepRef);
        }
    }

    /** 宿主接受的事件元数据。acceptance 由事实归并过程给出，不能从模型成长提案直接复制。 */
    record Evidence(String eventId, String saveId, int turn, Position position,
                    EvidenceKind kind, Acceptance acceptance, Set<String> abilityRefs) {
        Evidence {
            eventId = text(eventId);
            saveId = text(saveId);
            require(turn >= 0, Failure.INVALID_EVIDENCE);
            Objects.requireNonNull(position);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(acceptance);
            abilityRefs = refs(abilityRefs);
        }
    }

    /** 宿主冻结输入：世界轨道给出唯一合法后继，既有伤势等领域给出当前有效阻碍。无阅读节奏字段。 */
    record Context(String saveId, int turn, Position current, Position next,
                   Set<String> knownAbilities, Map<String, Evidence> events, Set<String> blockerEventRefs) {
        Context {
            saveId = text(saveId);
            require(turn > 0, Failure.SCOPE);
            Objects.requireNonNull(current);
            knownAbilities = refs(knownAbilities);
            events = Map.copyOf(events);
            blockerEventRefs = refs(blockerEventRefs);
        }
    }

    record Observation(String achieved, String limitations, Set<String> evidenceRefs) {
        Observation {
            achieved = text(achieved);
            limitations = text(limitations);
            evidenceRefs = refs(evidenceRefs);
        }
    }

    record Learning(String abilityRef, Observation observation) {
        Learning {
            abilityRef = text(abilityRef);
            Objects.requireNonNull(observation);
        }
    }

    record QualificationProposal(Readiness conclusion, Position target, String reason, Set<String> evidenceRefs) {
        QualificationProposal {
            Objects.requireNonNull(conclusion);
            reason = text(reason);
            evidenceRefs = refs(evidenceRefs);
            require(conclusion == Readiness.READY
                    ? target != null && !evidenceRefs.isEmpty()
                    : target == null && evidenceRefs.isEmpty(), Failure.INVALID_PROPOSAL);
        }
    }

    /** 成长提案不接受总行动成败开关，也没有加分、时长倍率、功法完全掌握或自动突破命令。 */
    record Proposal(List<Learning> learning, Observation accumulation, QualificationProposal qualification) {
        Proposal {
            learning = learning.stream().sorted(Comparator.comparing(Learning::abilityRef)).toList();
            require(learning.stream().map(Learning::abilityRef).distinct().count() == learning.size(),
                    Failure.INVALID_PROPOSAL);
            Objects.requireNonNull(qualification);
        }
    }

    record Request(String key, String saveId, long baseRevision, int turn, Proposal proposal) {
        Request {
            key = text(key);
            saveId = text(saveId);
            require(baseRevision >= 0 && turn > 0, Failure.SCOPE);
            Objects.requireNonNull(proposal);
        }
    }

    record Qualification(Position from, Position target, String reason, Set<String> evidenceRefs, int grantedTurn) {
        Qualification {
            Objects.requireNonNull(from);
            Objects.requireNonNull(target);
            reason = text(reason);
            evidenceRefs = refs(evidenceRefs);
        }
    }

    /** 仅保存最近一次接受请求的规范值；不会用全局“证据已消费”集合阻止经历复用。 */
    record Receipt(Request request) { }

    /** 测试投影版本只表示原型中的接受顺序，不等于真实 story_save 的结构版本或提交结果。 */
    record State(String saveId, long revision, int lastTurn, Position position,
                 Map<String, Observation> learning, Observation accumulation, Qualification qualification,
                 Set<String> blockerEventRefs, Receipt lastReceipt) {
        State {
            saveId = text(saveId);
            require(revision >= 0 && lastTurn >= 0, Failure.SCOPE);
            Objects.requireNonNull(position);
            learning = Map.copyOf(learning);
            blockerEventRefs = refs(blockerEventRefs);
        }

        // 仅说明准备和阻碍，不代表玩家已经授权尝试，也不保证突破成功。
        boolean hasUnblockedQualification() {
            return qualification != null && blockerEventRefs.isEmpty();
        }
    }

    record Result(State candidate, boolean replay) { }

    static final class Rejected extends IllegalArgumentException {
        private final Failure failure;

        Rejected(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    Result propose(State before, Context context, Request request) {
        require(before.saveId().equals(context.saveId()) && before.saveId().equals(request.saveId())
                && before.position().equals(context.current()), Failure.SCOPE);
        if (before.lastReceipt() != null && before.lastReceipt().request().key().equals(request.key())) {
            require(before.lastReceipt().request().equals(request), Failure.REQUEST_CONFLICT);
            return new Result(before, true);
        }
        require(before.revision() == request.baseRevision(), Failure.STALE);
        require(context.turn() == request.turn() && request.turn() == before.lastTurn() + 1, Failure.SCOPE);

        Proposal proposal = request.proposal();
        Map<String, Observation> learning = new TreeMap<>(before.learning());
        for (Learning change : proposal.learning()) {
            require(context.knownAbilities().contains(change.abilityRef()), Failure.INVALID_PROPOSAL);
            List<Evidence> evidence = experiences(context, change.observation().evidenceRefs());
            require(evidence.stream().allMatch(e -> e.abilityRefs().contains(change.abilityRef())),
                    Failure.INVALID_EVIDENCE);
            requireCurrentExperience(context, evidence);
            learning.put(change.abilityRef(), merge(learning.get(change.abilityRef()), change.observation()));
        }

        Observation accumulation = before.accumulation();
        if (proposal.accumulation() != null) {
            List<Evidence> evidence = experiences(context, proposal.accumulation().evidenceRefs());
            requireCurrentPosition(context, evidence);
            requireCurrentExperience(context, evidence);
            accumulation = merge(accumulation, proposal.accumulation());
        }

        Qualification qualification = before.qualification();
        QualificationProposal offered = proposal.qualification();
        if (offered.conclusion() == Readiness.READY) {
            require(context.next() != null && !context.current().equals(context.next())
                    && context.next().equals(offered.target()), Failure.INVALID_TARGET);
            requireCurrentPosition(context, experiences(context, offered.evidenceRefs()));
            if (qualification == null) {
                qualification = new Qualification(context.current(), offered.target(), offered.reason(),
                        offered.evidenceRefs(), context.turn());
            } else {
                require(qualification.from().equals(context.current())
                        && qualification.target().equals(offered.target()), Failure.INVALID_TARGET);
                // 再次说明准备充分只补充来源，保留首次形成资格的时间与理由。
                qualification = new Qualification(qualification.from(), qualification.target(), qualification.reason(),
                        union(qualification.evidenceRefs(), offered.evidenceRefs()), qualification.grantedTurn());
            }
        }
        // NONE / NOT_READY 不代表有证据的失格事件；原型不开放撤销或真正晋升接口。
        for (String ref : context.blockerEventRefs()) {
            require(evidence(context, ref).kind() == EvidenceKind.CONSTRAINT, Failure.INVALID_EVIDENCE);
        }

        return new Result(new State(before.saveId(), Math.addExact(before.revision(), 1), context.turn(),
                before.position(), learning, accumulation, qualification, context.blockerEventRefs(),
                new Receipt(request)), false);
    }

    private List<Evidence> experiences(Context context, Set<String> refs) {
        require(!refs.isEmpty(), Failure.INVALID_EVIDENCE);
        return refs.stream().map(ref -> {
            Evidence event = evidence(context, ref);
            require(event.kind() == EvidenceKind.EXPERIENCE, Failure.INVALID_EVIDENCE);
            return event;
        }).toList();
    }

    private Evidence evidence(Context context, String ref) {
        Evidence event = context.events().get(ref);
        require(event != null && event.eventId().equals(ref) && event.saveId().equals(context.saveId())
                && event.turn() <= context.turn(), Failure.INVALID_EVIDENCE);
        require(event.acceptance() == Acceptance.COMMITTED && event.turn() < context.turn()
                || event.acceptance() == Acceptance.ACCEPTED_THIS_TURN && event.turn() == context.turn(),
                Failure.INVALID_EVIDENCE);
        return event;
    }

    private void requireCurrentPosition(Context context, List<Evidence> evidence) {
        require(evidence.stream().allMatch(e -> e.position().equals(context.current())), Failure.INVALID_EVIDENCE);
    }

    private void requireCurrentExperience(Context context, List<Evidence> evidence) {
        // 新学习/积累须含本轮实际经历；旧依据可复用，但不能冒充又练了一次。
        require(evidence.stream().anyMatch(e -> e.turn() == context.turn()), Failure.INVALID_EVIDENCE);
    }

    private Observation merge(Observation before, Observation offered) {
        return before == null ? offered : new Observation(offered.achieved(), offered.limitations(),
                union(before.evidenceRefs(), offered.evidenceRefs()));
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> combined = new TreeSet<>(left);
        combined.addAll(right);
        return Set.copyOf(combined);
    }

    private static Set<String> refs(Set<String> values) {
        Set<String> normalized = new TreeSet<>();
        for (String value : values) {
            normalized.add(text(value));
        }
        return Set.copyOf(normalized);
    }

    private static String text(String value) {
        require(value != null && !value.isBlank(), Failure.INVALID_PROPOSAL);
        return value.trim();
    }

    private static void require(boolean condition, Failure failure) {
        if (!condition) {
            throw new Rejected(failure);
        }
    }
}
