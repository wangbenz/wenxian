package com.wenxian.v2.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.application.TurnProposalGenerator;
import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.TurnNarrativeContext;
import com.wenxian.v2.ai.domain.TurnProposal;
import com.wenxian.v2.ai.domain.TurnProposal.AbilityLearningProposal;
import com.wenxian.v2.ai.domain.TurnProposal.BreakthroughProposal;
import com.wenxian.v2.ai.domain.TurnProposal.ConfirmedFactProposal;
import com.wenxian.v2.ai.domain.TurnProposal.ConflictProposal;
import com.wenxian.v2.ai.domain.TurnProposal.DomainEffectProposal;
import com.wenxian.v2.ai.domain.TurnProposal.Generated;
import com.wenxian.v2.ai.domain.TurnProposal.HighImpactProposal;
import com.wenxian.v2.ai.domain.TurnProposal.RelationshipChangeProposal;
import com.wenxian.v2.ai.domain.TurnProposal.TurnChoice;
import com.wenxian.v2.game.application.SaveApplicationService;
import com.wenxian.v2.game.application.SaveApplicationService.CreateSaveCommand;
import com.wenxian.v2.game.application.TurnApplicationService;
import com.wenxian.v2.game.application.TurnApplicationService.SubmitActionCommand;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.domain.Race;
import com.wenxian.v2.game.domain.RealmLadder;
import com.wenxian.v2.game.domain.WorldCalendar;
import com.wenxian.v2.game.persistence.TurnRepository.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Runs anonymous V0.13 player-behavior weights through the full V0.2 turn pipeline.
 * Only model generation is deterministic here; authority assembly, adjudication,
 * normalization, domain validation, atomic commit and persistence are production code.
 */
@SpringBootTest
@Tag("long-run")
class V2PlayerBehaviorLongRunTest {
    private static final Set<String> QINGQI_PLAYABLE_LOCATIONS = Set.of(
            "loc-qingmu-town", "loc-qingmu-east-gate", "loc-zhaogu-mine",
            "loc-huichun-hall", "loc-bronze-gate", "loc-northern-road");
    private static final List<String> TOWN_BRANCHES = List.of(
            "loc-qingmu-east-gate", "loc-zhaogu-mine", "loc-huichun-hall",
            "loc-bronze-gate", "loc-northern-road");
    private static final Set<String> TOWN_PRICE_LOCATIONS = Set.of(
            "loc-qingmu-town", "loc-qingmu-east-gate", "loc-huichun-hall");
    private static final Map<Race, String> TALENTS = Map.of(
            Race.HUMAN, "ability-human-clear-heart",
            Race.DEMON, "ability-demon-night-instinct",
            Race.SPIRIT, "ability-spirit-object-memory");
    private static final List<Integer> BREAKTHROUGH_CULTIVATION_TURNS = List.of(12, 28, 43);

    @Autowired
    SaveApplicationService saves;

    @Autowired
    TurnApplicationService turns;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    TurnProposalGenerator proposals;

    private final Map<String, AtomicInteger> cultivationCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> combatCounts = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> factCounts = new ConcurrentHashMap<>();

    @BeforeEach
    void generateCorpusProposals() {
        cultivationCounts.clear();
        combatCounts.clear();
        factCounts.clear();
        when(proposals.generate(any())).thenAnswer(invocation ->
                proposalFor(invocation.getArgument(0, TurnNarrativeContext.class)));
    }

    @Test
    void simulatesOneThousandHistoricallyWeightedTurnsAcrossAllThreeRaces() throws Exception {
        Map<String, Integer> target = profileCounts();
        List<List<String>> plans = distributedPlans(target);
        List<Journey> journeys = List.of(
                createJourney(Race.HUMAN, "沈砚舟", "寒门行脚人"),
                createJourney(Race.DEMON, "青尾", "北境青狐"),
                createJourney(Race.SPIRIT, "石问", "照骨矿灵"));
        long[] previousMinutes = journeys.stream().mapToLong(journey -> journey.current().worldMinute()).toArray();
        int[] successfulTurns = new int[3];
        int[] waitCounts = new int[3];
        int[] travelCounts = new int[3];
        Map<String, Integer> executed = new LinkedHashMap<>();

        int maximumPlan = plans.stream().mapToInt(List::size).max().orElseThrow();
        for (int index = 0; index < maximumPlan; index++) {
            for (int journeyIndex = 0; journeyIndex < journeys.size(); journeyIndex++) {
                if (index >= plans.get(journeyIndex).size()) continue;
                Journey journey = journeys.get(journeyIndex);
                String category = plans.get(journeyIndex).get(index);
                SaveAggregate before = journey.current();
                SubmitActionCommand command = commandFor(category, before, waitCounts, travelCounts, journeyIndex);
                Operation operation = await(journey.userId(), turns.submit(journey.userId(), before.save().id(), command));
                assertThat(operation.status()).as("%s turn %s (%s): %s | %s | %s", journey.race(),
                                index + 1, category, operation.errorCode(), operation.errorMessage(),
                                String.join("；", operation.errorSuggestions()))
                        .isEqualTo("SUCCEEDED");

                SaveAggregate after = saves.requireAggregate(before.save().id(), journey.userId());
                journey.current(after);
                successfulTurns[journeyIndex]++;
                executed.merge(category, 1, Integer::sum);
                assertCoreInvariants(journey, after, successfulTurns[journeyIndex], previousMinutes[journeyIndex]);
                previousMinutes[journeyIndex] = after.worldMinute();
            }
        }

        assertThat(executed).containsExactlyInAnyOrderEntriesOf(target);
        assertThat(journeys.stream().mapToInt(journey -> journey.current().save().turnNumber()).sum())
                .isEqualTo(1_000);
        for (Journey journey : journeys) {
            assertJourneyOutcome(journey);
        }
    }

    @Test
    void rejectsRepresentativeUnsafePlayerPatternsWithoutMutatingTheSave() throws Exception {
        Journey journey = createJourney(Race.HUMAN, "顾谨", "青木镇学徒");
        assertRejectedWithoutMutation(journey, "我直接宣布自己已经突破金丹并获得全州认可",
                "OUTCOME_CANNOT_BE_DECLARED");
        assertRejectedWithoutMutation(journey, "施展并未掌握的天外神通击穿青岐州",
                "ABILITY_NOT_MASTERED");
        assertRejectedWithoutMutation(journey, "使用并不存在的仙丹立刻恢复全部伤势",
                "ITEM_NOT_OWNED");
        assertRejectedWithoutMutation(journey, "一步前往青岐州外的天外天",
                "LOCATION_UNKNOWN");
        assertRejectedWithoutMutation(journey, "用五十文购买一块粗粮干饼并直接成交",
                "AI_DOMAIN_EFFECT_INVALID");
    }

    private Journey createJourney(Race race, String name, String rootType) {
        String userId = "v2-corpus-" + race.name().toLowerCase() + "-" + UUID.randomUUID();
        jdbc.update("insert into app_user(id,email,status,registered_at) values (?,?,'ACTIVE',?)",
                userId, userId + "@wenxian.invalid", Timestamp.from(Instant.now()));
        SaveAggregate aggregate = saves.create(userId, new CreateSaveCommand(name,
                race == Race.SPIRIT ? "不设定" : "女", race.name(), rootType,
                "青岐州中一段可被时间与他人共同验证的普通来历",
                "在不否定来路的前提下走出自己的修行道路", "NORMAL", TALENTS.get(race)));
        jdbc.update("update v2_wallet set balance=200 where save_id=? and currency_code='WEN'",
                aggregate.save().id());
        seedRations(aggregate);
        seedLocalCharacters(aggregate);
        aggregate = saves.requireAggregate(aggregate.save().id(), userId);
        return new Journey(userId, race, name, aggregate, new BigDecimal("200"));
    }

    private void seedRations(SaveAggregate aggregate) {
        String inventoryId = jdbc.queryForObject(
                "select id from v2_inventory where save_id=? and owner_character_id=?",
                String.class, aggregate.save().id(), aggregate.protagonist().id());
        jdbc.update("""
                insert into v2_item_instance(id,save_id,inventory_id,item_definition_id,quantity,item_status,
                  condition_summary,provenance_summary,acquired_world_minute,state_version,updated_at)
                values (?,?,?,'item-coarse-ration',40,'AVAILABLE','干燥完好','长线测试出发前准备',?,0,?)
                """, "v2-item-corpus-" + UUID.randomUUID(), aggregate.save().id(), inventoryId,
                aggregate.worldMinute(), Timestamp.from(Instant.now()));
    }

    private void seedLocalCharacters(SaveAggregate aggregate) {
        int index = 0;
        for (String locationId : QINGQI_PLAYABLE_LOCATIONS) {
            index++;
            String npcId = "v2-npc-corpus-" + UUID.randomUUID();
            String npcName = aggregate.protagonist().name() + "所识" + index;
            var realm = RealmLadder.initial(aggregate.protagonist().race());
            Instant now = Instant.now();
            jdbc.update("""
                    insert into v2_character(id,save_id,character_kind,name,gender_expression,race,root_type,
                      root_description,life_goal,status,created_world_minute,created_at,updated_at)
                    values (?,?,'NPC',?,'不设定',?,?,'常年生活在当地、行动可被持续追踪的普通人物',
                      '守住自己的生计与承诺','ALIVE',?,?,?)
                    """, npcId, aggregate.save().id(), npcName, aggregate.protagonist().race().name(),
                    "本地居民", aggregate.worldMinute(), Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    insert into v2_character_state(character_id,location_id,life_stage_code,realm_code,
                      realm_display_name,realm_phase,public_identity,body_condition,current_goal,state_version,updated_at)
                    values (?,?,'ADULT',?,?,'INITIAL','当地常住者','安稳','继续自己的日常生活',0,?)
                    """, npcId, locationId, realm.code(), realm.displayName(), Timestamp.from(now));
        }
    }

    private SubmitActionCommand commandFor(String category, SaveAggregate aggregate,
                                            int[] waitCounts, int[] travelCounts, int journeyIndex) {
        String requestId = "corpus-" + category.toLowerCase() + "-" + UUID.randomUUID();
        return switch (category) {
            case "WAIT" -> {
                int count = ++waitCounts[journeyIndex];
                long minutes = count % 7 == 0
                        ? 3L * WorldCalendar.MINUTES_PER_YEAR
                        : 7L * WorldCalendar.MINUTES_PER_DAY;
                String text = count % 7 == 0 ? "在安全处闭关三年，只整理已有所得" : "在安全处休整七日并观察局势变化";
                yield new SubmitActionCommand(requestId, aggregate.save().stateVersion(), "WAIT",
                        text, minutes, null);
            }
            case "TRAVEL" -> {
                String current = aggregate.characterState().locationId();
                String target = "loc-qingmu-town".equals(current)
                        ? TOWN_BRANCHES.get(travelCounts[journeyIndex]++ % TOWN_BRANCHES.size())
                        : "loc-qingmu-town";
                yield new SubmitActionCommand(requestId, aggregate.save().stateVersion(), "TRAVEL",
                        "沿已知道路前往" + locationName(target), null, target);
            }
            default -> new SubmitActionCommand(requestId, aggregate.save().stateVersion(), "FREE",
                    actionText(category), null, null);
        };
    }

    private Generated proposalFor(TurnNarrativeContext context) {
        String action = context.authority().actionText();
        if (action.contains("直接宣布自己")) {
            return generated(context, intent("CULTIVATE", context, null, null, List.of(), List.of(),
                    "GOLDEN_CORE", true), 60, "ACTION_SUCCEEDED", List.of(), HighImpactProposal.empty());
        }
        if (action.contains("并未掌握的天外神通")) {
            return generated(context, intent("USE_ABILITY", context, null, null, List.of(),
                    List.of("ability-not-owned"), null, false), 60, "ACTION_PROGRESSED",
                    List.of(), HighImpactProposal.empty());
        }
        if (action.contains("并不存在的仙丹")) {
            return generated(context, intent("USE_ITEM", context, null, null,
                    List.of("item-not-owned"), List.of(), null, false), 30,
                    "ACTION_PROGRESSED", List.of(), HighImpactProposal.empty());
        }
        if (action.contains("青岐州外的天外天")) {
            return generated(context, intent("TRAVEL", context, "loc-outside-qingqi", null,
                    List.of(), List.of(), null, false), 60, "ACTION_PROGRESSED",
                    List.of(), HighImpactProposal.empty());
        }
        if (action.contains("五十文购买")) {
            return generated(context, intent("TRADE", context, null, null, List.of(), List.of(), null, false),
                    120, "ACTION_SUCCEEDED", List.of(
                            effect("WALLET_DEBIT", null, null, "WEN", "50", null, "支付明显超出当地物价的钱"),
                            effect("ITEM_ACQUIRED", null, "item-coarse-ration", null, "1", null, "购入一块干饼")),
                    HighImpactProposal.empty());
        }

        if (action.contains("沿途查验")) return investigateProposal(context);
        if (action.contains("与当地人交谈")) return interactProposal(context);
        if (action.contains("按已有心得修行")) return cultivateProposal(context);
        if (action.contains("施展已掌握能力")) return abilityProposal(context);
        if (action.contains("食用随身干粮")) return itemProposal(context);
        if (action.contains("在当地询价交易")) return tradeProposal(context);
        if (action.contains("与拦路者交锋")) return combatProposal(context);
        return generated(context, intent("OTHER", context, null, null, List.of(), List.of(), null, false),
                60, "ACTION_PROGRESSED", List.of(), HighImpactProposal.empty());
    }

    private Generated investigateProposal(TurnNarrativeContext context) {
        int fact = factCounts.computeIfAbsent(context.authority().saveId(), ignored -> new AtomicInteger())
                .incrementAndGet();
        ActionIntent intent = intent("INVESTIGATE", context, null, null, List.of(), List.of(), null, false);
        TurnProposal proposal = proposal(intent, WorldCalendar.MINUTES_PER_DAY, "INFORMATION_DISCOVERED",
                List.of(), HighImpactProposal.empty(), List.of(new ConfirmedFactProposal(
                        "KNOWLEDGE", context.authority().saveId(), "第" + fact + "次查验确认了当前地点一处可复核的细节")));
        return new Generated(proposal, "TEST", "historical-corpus-fixture");
    }

    private Generated interactProposal(TurnNarrativeContext context) {
        var npc = localNpc(context);
        boolean knowsBreathGuidance = context.authority().abilityCandidates().stream()
                .anyMatch(ability -> "循息法".equals(ability.name()));
        List<AbilityLearningProposal> learning = knowsBreathGuidance ? List.of() : List.of(
                new AbilityLearningProposal("ability-breath-guidance", npc.id(),
                        "在长期交谈与示范中掌握了最基础的循息方法"));
        HighImpactProposal highImpact = new HighImpactProposal(List.of(), List.of(
                new RelationshipChangeProposal(npc.id(), "ACQUAINTANCE", 1, 0, 0, 1,
                        "围绕当地近况进行了一次有来有往的交谈")), learning, null, null, null);
        return generated(context, intent("INTERACT", context, null, npc.id(), List.of(), List.of(), null, false),
                120, "ACTION_SUCCEEDED", List.of(), highImpact);
    }

    private Generated cultivateProposal(TurnNarrativeContext context) {
        int count = cultivationCounts.computeIfAbsent(context.authority().saveId(), ignored -> new AtomicInteger())
                .incrementAndGet();
        boolean breakthrough = BREAKTHROUGH_CULTIVATION_TURNS.contains(count)
                && context.authority().protagonist().nextRealmCode() != null;
        String targetRealm = breakthrough ? context.authority().protagonist().nextRealmCode() : null;
        ActionIntent intent = intent("CULTIVATE", context, null, null, List.of(), List.of(), targetRealm, false);
        List<DomainEffectProposal> effects = List.of(effect("CULTIVATION_INSIGHT",
                context.authority().protagonist().id(), null, null, null,
                "第" + count + "次修行确认气机需要与身体和时辰相合", "按月修行后留下可继续验证的心得"));
        HighImpactProposal highImpact = breakthrough
                ? new HighImpactProposal(List.of(), List.of(), List.of(),
                new BreakthroughProposal(targetRealm, "此前多次修行已形成连续心得，本次只跨越相邻阶段"),
                null, null)
                : HighImpactProposal.empty();
        return generated(context, intent, 30L * WorldCalendar.MINUTES_PER_DAY,
                breakthrough ? "ACTION_SUCCEEDED" : "ACTION_PROGRESSED", effects, highImpact);
    }

    private Generated abilityProposal(TurnNarrativeContext context) {
        var ability = context.authority().abilityCandidates().getFirst();
        ActionIntent intent = intent("USE_ABILITY", context, null, null, List.of(), List.of(ability.id()),
                null, false);
        return generated(context, intent, 60, "ACTION_SUCCEEDED", List.of(effect("ABILITY_USED", ability.id(),
                null, null, null, null, "在能力边界内完成一次实际施展")), HighImpactProposal.empty());
    }

    private Generated itemProposal(TurnNarrativeContext context) {
        var ration = context.authority().itemCandidates().stream()
                .filter(item -> "粗粮干饼".equals(item.name()) && "AVAILABLE".equals(item.state()))
                .findFirst().orElseThrow();
        ActionIntent intent = intent("USE_ITEM", context, null, null, List.of(ration.id()), List.of(), null, false);
        return generated(context, intent, 30, "ACTION_SUCCEEDED", List.of(effect("ITEM_CONSUMED", ration.id(),
                null, null, "1", null, "赶路或休整时食用一块已有干粮")), HighImpactProposal.empty());
    }

    private Generated tradeProposal(TurnNarrativeContext context) {
        ActionIntent intent = intent("TRADE", context, null, null, List.of(), List.of(), null, false);
        List<DomainEffectProposal> effects = TOWN_PRICE_LOCATIONS.contains(context.authority().currentLocationId())
                ? List.of(effect("WALLET_DEBIT", null, null, "WEN", "3", null, "按当地常价支付三文"),
                effect("ITEM_ACQUIRED", null, "item-coarse-ration", null, "1", null, "购入一块粗粮干饼"))
                : List.of();
        return generated(context, intent, 120, effects.isEmpty() ? "ACTION_PROGRESSED" : "ACTION_SUCCEEDED",
                effects, HighImpactProposal.empty());
    }

    private Generated combatProposal(TurnNarrativeContext context) {
        var npc = localNpc(context);
        int count = combatCounts.computeIfAbsent(context.authority().saveId(), ignored -> new AtomicInteger())
                .incrementAndGet();
        String result = count % 4 == 0 ? "VICTORY" : "STALEMATE";
        HighImpactProposal highImpact = new HighImpactProposal(List.of(), List.of(), List.of(), null,
                new ConflictProposal(npc.id(), result, "双方依照当前境界与现场条件完成一次有限交锋"), null);
        return generated(context, intent("COMBAT", context, null, npc.id(), List.of(), List.of(), null, false),
                60, "VICTORY".equals(result) ? "ACTION_SUCCEEDED" : "ACTION_PROGRESSED",
                List.of(), highImpact);
    }

    private com.wenxian.v2.ai.domain.IntentContext.EntityCandidate localNpc(TurnNarrativeContext context) {
        String location = "位置=" + context.authority().currentLocationName() + "；";
        return context.authority().characterCandidates().stream()
                .filter(character -> "NPC".equals(character.type())
                        && character.details() != null && character.details().contains(location))
                .findFirst().orElseThrow(() -> new AssertionError("missing local NPC at " + location));
    }

    private ActionIntent intent(String type, TurnNarrativeContext context, String locationId, String characterId,
                                List<String> itemIds, List<String> abilityIds, String realm, boolean declared) {
        return new ActionIntent(type, declared ? "DECLARE_OUTCOME" : "ATTEMPT", null,
                locationId, characterId, itemIds, abilityIds, realm, declared, List.of(),
                context.authority().actionText());
    }

    private Generated generated(TurnNarrativeContext context, ActionIntent intent, long minutes,
                                String outcome, List<DomainEffectProposal> effects,
                                HighImpactProposal highImpact) {
        return new Generated(proposal(intent, minutes, outcome, effects, highImpact, List.of()),
                "TEST", "historical-corpus-fixture");
    }

    private TurnProposal proposal(ActionIntent intent, long minutes, String outcome,
                                  List<DomainEffectProposal> effects, HighImpactProposal highImpact,
                                  List<ConfirmedFactProposal> facts) {
        String summary = switch (intent.actionType()) {
            case "INVESTIGATE" -> "本次查验获得了一条可以继续复核的当地信息";
            case "INTERACT" -> "交谈改变了与当地人物之间可被持续记录的关系";
            case "CULTIVATE" -> "本次修行只沉淀已有道路上的心得与相邻进展";
            case "USE_ABILITY" -> "已掌握的能力在既有边界内完成了一次施展";
            case "USE_ITEM" -> "行囊中真实存在的物品被按数量使用";
            case "TRADE" -> effects.isEmpty() ? "只询问了当地价格，没有强行成交" : "交易按当地物价完成";
            case "COMBAT" -> "交锋结果没有越过双方当前境界与现场条件";
            default -> "行动在当前世界规则内产生了有限进展";
        };
        String narrative = ("你依照当前所处地点、已经掌握的信息与自身能力采取行动。过程没有凭空改变身份、境界或物品，"
                + "旁人的反应也只来自眼前发生的事。行动结束后，时间继续向前，能够被验证的变化才被写入命卷。\n\n").repeat(2);
        return new TurnProposal(intent, minutes, outcome, summary, narrative, facts, effects, highImpact,
                List.of(new TurnChoice("继续当前方向", "依据刚确认的结果继续谨慎行动"),
                        new TurnChoice("重新观察局势", "先核对人物、地点与行囊再决定下一步")));
    }

    private DomainEffectProposal effect(String type, String entityId, String definitionId, String currency,
                                        String amount, String value, String reason) {
        return new DomainEffectProposal(type, entityId, definitionId, currency,
                amount == null ? null : new BigDecimal(amount), value, reason);
    }

    private void assertRejectedWithoutMutation(Journey journey, String actionText, String expectedCode)
            throws InterruptedException {
        SaveAggregate before = journey.current();
        Operation operation = await(journey.userId(), turns.submit(journey.userId(), before.save().id(),
                new SubmitActionCommand("unsafe-" + UUID.randomUUID(), before.save().stateVersion(),
                        "FREE", actionText, null, null)));
        assertThat(operation.status()).isEqualTo("REJECTED");
        assertThat(operation.errorCode()).isEqualTo(expectedCode);
        SaveAggregate after = saves.requireAggregate(before.save().id(), journey.userId());
        assertThat(after.save().turnNumber()).isEqualTo(before.save().turnNumber());
        assertThat(after.save().stateVersion()).isEqualTo(before.save().stateVersion());
        assertThat(after.worldMinute()).isEqualTo(before.worldMinute());
        assertThat(after.characterState().realmCode()).isEqualTo(before.characterState().realmCode());
        assertThat(after.inventory()).isEqualTo(before.inventory());
        assertThat(after.wallet()).isEqualTo(before.wallet());
        journey.current(after);
    }

    private void assertCoreInvariants(Journey journey, SaveAggregate aggregate, int expectedTurns,
                                      long previousWorldMinute) {
        assertThat(aggregate.save().turnNumber()).isEqualTo(expectedTurns);
        assertThat(aggregate.save().stateVersion()).isEqualTo(expectedTurns);
        assertThat(aggregate.save().status()).isEqualTo("ACTIVE");
        assertThat(aggregate.protagonist().name()).isEqualTo(journey.name());
        assertThat(aggregate.protagonist().race()).isEqualTo(journey.race());
        assertThat(aggregate.worldMinute()).isGreaterThan(previousWorldMinute);
        assertThat(aggregate.characterState().locationId()).isIn(QINGQI_PLAYABLE_LOCATIONS);
        assertThat(RealmLadder.rank(journey.race(), aggregate.characterState().realmCode())).isBetween(0, 3);
        assertThat(aggregate.inventory()).allSatisfy(item -> assertThat(item.quantity()).isNotNegative());
        assertThat(aggregate.wallet()).allSatisfy(wallet -> assertThat(wallet.balance()).isNotNegative());
        assertThat(aggregate.knownCharacters().stream().map(character -> character.name()).distinct().count())
                .isEqualTo(aggregate.knownCharacters().size());
    }

    private void assertJourneyOutcome(Journey journey) {
        SaveAggregate aggregate = journey.current();
        assertThat(RealmLadder.rank(journey.race(), aggregate.characterState().realmCode())).isEqualTo(3);
        assertThat(aggregate.abilities()).extracting(ability -> ability.name())
                .contains("循息法")
                .containsAnyOf("心窍澄明", "夜行本能", "物忆");
        assertThat(aggregate.knownCharacters()).hasSize(6);
        assertThat(aggregate.relationships()).isNotEmpty();
        assertThat(aggregate.worldDate().year()).isGreaterThan(3008);

        assertChangeCount(journey, "REALM_BREAKTHROUGH", 3);
        assertChangeCountAtLeast(journey, "ABILITY_USED", 1);
        assertChangeCountAtLeast(journey, "ITEM_CONSUMED", 1);
        assertChangeCountAtLeast(journey, "ITEM_ACQUIRED", 1);
        assertChangeCountAtLeast(journey, "WALLET_DEBIT", 1);
        assertChangeCountAtLeast(journey, "RELATIONSHIP_CHANGED", 1);
        assertChangeCountAtLeast(journey, "CONFLICT_RESOLVED", 1);
        assertChangeCountAtLeast(journey, "LOCATION_CHANGED", 1);

        List<String> locations = jdbc.queryForList("""
                select after_payload from v2_domain_change
                where save_id=? and change_type='LOCATION_CHANGED'
                """, String.class, aggregate.save().id());
        assertThat(locations).allMatch(QINGQI_PLAYABLE_LOCATIONS::contains);

        BigDecimal wallet = jdbc.queryForObject("""
                select balance from v2_wallet where save_id=? and currency_code='WEN'
                """, BigDecimal.class, aggregate.save().id());
        BigDecimal ledger = jdbc.queryForObject("""
                select coalesce(sum(case when transaction_type='DEBIT' then -amount else amount end),0)
                from v2_wallet_ledger
                where save_id=? and wallet_id=(select id from v2_wallet where save_id=? and currency_code='WEN')
                """, BigDecimal.class, aggregate.save().id(), aggregate.save().id());
        assertThat(wallet).isEqualByComparingTo(journey.initialWen().add(ledger));
    }

    private void assertChangeCount(Journey journey, String changeType, int expected) {
        assertThat(jdbc.queryForObject("""
                select count(*) from v2_domain_change where save_id=? and change_type=?
                """, Integer.class, journey.current().save().id(), changeType)).isEqualTo(expected);
    }

    private void assertChangeCountAtLeast(Journey journey, String changeType, int minimum) {
        assertThat(jdbc.queryForObject("""
                select count(*) from v2_domain_change where save_id=? and change_type=?
                """, Integer.class, journey.current().save().id(), changeType)).isGreaterThanOrEqualTo(minimum);
    }

    private List<List<String>> distributedPlans(Map<String, Integer> target) {
        List<List<String>> plans = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        int categoryOffset = 0;
        for (var entry : target.entrySet()) {
            for (int occurrence = 0; occurrence < entry.getValue(); occurrence++) {
                plans.get((occurrence + categoryOffset) % plans.size()).add(entry.getKey());
            }
            categoryOffset++;
        }
        for (int index = 0; index < plans.size(); index++) {
            Collections.shuffle(plans.get(index), new Random(20260803L + index));
        }
        moveFirst(plans.get(0), "TRADE", 0);
        moveFirst(plans.get(0), "INTERACT", 1);
        for (int index = 1; index < plans.size(); index++) {
            moveFirst(plans.get(index), "INTERACT", 0);
            moveFirst(plans.get(index), "TRAVEL", 1);
            moveFirst(plans.get(index), "TRADE", 2);
        }
        return plans;
    }

    private void moveFirst(List<String> plan, String category, int position) {
        int found = plan.indexOf(category);
        assertThat(found).isNotNegative();
        plan.remove(found);
        plan.add(Math.min(position, plan.size()), category);
    }

    private Map<String, Integer> profileCounts() throws Exception {
        JsonNode profile;
        try (InputStream input = getClass().getResourceAsStream("/corpus/v013-action-profile.json")) {
            assertThat(input).isNotNull();
            profile = json.readTree(input);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        profile.path("testTargetPer1000").fields().forEachRemaining(field ->
                counts.put(field.getKey(), field.getValue().asInt()));
        return counts;
    }

    private String actionText(String category) {
        return switch (category) {
            case "INVESTIGATE" -> "沿途查验当地痕迹，把亲眼所见与传闻分开记录";
            case "INTERACT" -> "与当地人交谈近来的变化，不替对方预设回答";
            case "CULTIVATE" -> "按已有心得修行一个月，只尝试当前道路上的真实进展";
            case "USE_ABILITY" -> "施展已掌握能力处理眼前问题，不扩大它原有的边界";
            case "USE_ITEM" -> "食用随身干粮后继续观察，不凭空增加行囊物品";
            case "TRADE" -> "在当地询价交易，只接受符合钱包和物价的成交结果";
            case "COMBAT" -> "与拦路者交锋并保留退路，让结果由双方实力与现场决定";
            default -> "处理一件不属于修炼或战斗的日常事务，观察它带来的有限变化";
        };
    }

    private String locationName(String locationId) {
        return switch (locationId) {
            case "loc-qingmu-town" -> "青木镇";
            case "loc-qingmu-east-gate" -> "青木镇东门";
            case "loc-zhaogu-mine" -> "照骨矿";
            case "loc-huichun-hall" -> "回春堂";
            case "loc-bronze-gate" -> "青铜门";
            case "loc-northern-road" -> "北境驿道";
            default -> locationId;
        };
    }

    private Operation await(String userId, Operation operation) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!operation.status().matches("SUCCEEDED|REJECTED|FAILED") && Instant.now().isBefore(deadline)) {
            Thread.sleep(2);
            operation = turns.requireOperation(userId, operation.id());
        }
        return operation;
    }

    private static final class Journey {
        private final String userId;
        private final Race race;
        private final String name;
        private SaveAggregate current;
        private final BigDecimal initialWen;

        private Journey(String userId, Race race, String name, SaveAggregate current, BigDecimal initialWen) {
            this.userId = userId;
            this.race = race;
            this.name = name;
            this.current = current;
            this.initialWen = initialWen;
        }

        String userId() { return userId; }
        Race race() { return race; }
        String name() { return name; }
        SaveAggregate current() { return current; }
        void current(SaveAggregate value) { current = value; }
        BigDecimal initialWen() { return initialWen; }
    }
}
