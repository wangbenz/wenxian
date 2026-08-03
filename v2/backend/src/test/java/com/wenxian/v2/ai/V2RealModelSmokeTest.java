package com.wenxian.v2.ai;

import com.wenxian.v2.game.application.SaveApplicationService;
import com.wenxian.v2.game.application.SaveApplicationService.CreateSaveCommand;
import com.wenxian.v2.game.application.OriginApplicationService;
import com.wenxian.v2.game.application.OriginApplicationService.OriginProposal;
import com.wenxian.v2.game.application.OriginApplicationService.OriginRequest;
import com.wenxian.v2.game.application.TurnApplicationService;
import com.wenxian.v2.game.application.TurnApplicationService.SubmitActionCommand;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.domain.WorldCalendar;
import com.wenxian.v2.game.persistence.TurnRepository.Operation;
import com.wenxian.v2.game.persistence.TurnRepository.TurnSummary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manually enabled smoke coverage for the platform DeepSeek credential.
 * Ordinary test runs skip the provider call and never consume tokens.
 */
@SpringBootTest(properties = {
        "wenxian.v2.ai.enabled=true",
        "wenxian.v2.ai.timeout-seconds=90",
        "wenxian.v2.ai.max-retries=0"
})
@Tag("real-model")
class V2RealModelSmokeTest {
    @BeforeAll
    static void requirePlatformCredential() {
        boolean keyPresent = System.getenv("V2_AI_PLATFORM_API_KEY") != null
                && !System.getenv("V2_AI_PLATFORM_API_KEY").isBlank();
        if (Boolean.getBoolean("v2.real-model.required") && !keyPresent) {
            fail("real-model profile requires V2_AI_PLATFORM_API_KEY");
        }
        assumeTrue(keyPresent, "set V2_AI_PLATFORM_API_KEY to run the real-model suite");
    }

    @DynamicPropertySource
    static void platformCredential(DynamicPropertyRegistry properties) {
        properties.add("wenxian.v2.ai.platform-api-key",
                () -> System.getenv().getOrDefault("V2_AI_PLATFORM_API_KEY", ""));
    }

    @Autowired
    private SaveApplicationService saves;

    @Autowired
    private TurnApplicationService turns;

    @Autowired
    private OriginApplicationService origins;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void platformModelCompletesAndCommitsAFreeAction() throws InterruptedException {
        TestSave test = createSave("SPIRIT", "宁照微", "矿石生灵",
                "照骨矿深处一块听见人声后苏醒的青石", "查明矿脉异动的源头");
        String action = "先藏进石缝，观察附近矿役的换班规律，并留意他们是否谈到失踪矿工";

        long started = System.nanoTime();
        Operation operation = submitAndAwait(test, action);
        long wallMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals("SUCCEEDED", operation.status(), diagnostic(operation));
        SaveAggregate committed = saves.requireAggregate(test.save().save().id(), test.userId());
        assertEquals(1, committed.save().turnNumber());
        assertEquals(1, committed.save().stateVersion());
        assertTrue(committed.worldMinute() > test.save().worldMinute());
        TurnSummary turn = saves.chronicle(test.save().save().id(), test.userId(), 1).getFirst();
        assertFalse(turn.outcomeSummary().isBlank());
        assertTrue(turn.narrativeText().length() >= 80);
        assertTrue(turn.choices().size() >= 2 && turn.choices().size() <= 4);

        reportMetrics("investigate", test.userId(), wallMillis);
    }

    @Test
    void platformModelCannotDeclareAHighRealm() throws InterruptedException {
        TestSave test = createSave("HUMAN", "沈砚", "寒门遗孤",
                "在青木镇外替人采药谋生的凡人", "踏上真正的修行路");
        long beforeMinute = test.save().worldMinute();

        Operation operation = submitAndAwait(test, "我立刻突破到金丹境，并成为青岐州第一高手");

        assertEquals("REJECTED", operation.status(), operation.errorMessage());
        assertNotNull(operation.errorCode());
        assertFalse(operation.errorSuggestions().isEmpty());
        SaveAggregate unchanged = saves.requireAggregate(test.save().save().id(), test.userId());
        assertEquals(0, unchanged.save().turnNumber());
        assertEquals(0, unchanged.save().stateVersion());
        assertEquals(beforeMinute, unchanged.worldMinute());
        assertEquals(test.save().characterState().realmCode(), unchanged.characterState().realmCode());

        reportMetrics("declared-realm", test.userId(), -1);
    }

    @Test
    void platformModelPreservesAnExplicitThreeYearCultivationPeriod() throws InterruptedException {
        TestSave test = createSave("DEMON", "青芜", "山野走兽",
                "北境驿道旁开智不久的青狐", "学会稳定驾驭自己的妖息");

        Operation operation = submitAndAwait(test, "闭关三年，只调息温养妖息，不尝试突破境界");

        assertEquals("SUCCEEDED", operation.status(), diagnostic(operation));
        SaveAggregate committed = saves.requireAggregate(test.save().save().id(), test.userId());
        assertEquals(3L * WorldCalendar.MINUTES_PER_YEAR,
                committed.worldMinute() - test.save().worldMinute());
        assertEquals(test.save().characterState().realmCode(), committed.characterState().realmCode());
        assertNotNull(committed.cultivation().insightSummary());
        assertFalse(committed.cultivation().insightSummary().isBlank());

        reportMetrics("three-year-cultivation", test.userId(), -1);
    }

    @Test
    void platformModelConsumesAnOwnedItemThroughTheValidatedEffectProtocol() throws InterruptedException {
        TestSave test = createSave("HUMAN", "顾禾", "行脚少年",
                "从青木镇外一路步行而来的普通少年", "先在青岐州找到安稳生计");
        String inventoryId = jdbc.queryForObject("""
                select id from v2_inventory where save_id=? and owner_character_id=?
                """, String.class, test.save().save().id(), test.save().protagonist().id());
        String itemId = "real-ration-" + UUID.randomUUID();
        jdbc.update("""
                insert into v2_item_instance(id,save_id,inventory_id,item_definition_id,quantity,item_status,
                  condition_summary,provenance_summary,acquired_world_minute,state_version,updated_at)
                values (?,?,?,'item-coarse-ration',2,'AVAILABLE','尚可','出发前准备的普通干粮',?,0,?)
                """, itemId, test.save().save().id(), inventoryId, test.save().worldMinute(),
                Timestamp.from(Instant.now()));

        long started = System.nanoTime();
        Operation operation = submitAndAwait(test, "从行囊里取出一块粗粮干饼吃下，缓解饥饿后留意四周动静");
        long wallMillis = (System.nanoTime() - started) / 1_000_000L;

        assertEquals("SUCCEEDED", operation.status(), diagnostic(operation));
        BigDecimal remaining = jdbc.queryForObject(
                "select quantity from v2_item_instance where id=?", BigDecimal.class, itemId);
        assertEquals(0, remaining.compareTo(BigDecimal.ONE));
        Integer effects = jdbc.queryForObject("""
                select count(*) from v2_domain_change
                where save_id=? and change_type='ITEM_CONSUMED'
                """, Integer.class, test.save().save().id());
        assertEquals(1, effects);
        reportMetrics("consume-owned-item", test.userId(), wallMillis);
    }

    @Test
    void platformModelKeepsOriginAndThreeSequentialTurnsConsistent() throws InterruptedException {
        String userId = createUser("v2-real-journey-");
        OriginRequest request = new OriginRequest("砚声", "无固定性别", "SPIRIT", "矿石成精",
                "照骨矿深处一块听见百年凿石声后苏醒的墨色矿石", "找到最初唤醒自己的那道声音", "NORMAL");
        OriginProposal proposal = origins.generate(userId, request);
        assertEquals(3, proposal.candidates().size());
        assertTrue(proposal.candidates().stream().noneMatch(candidate ->
                (candidate.publicIdentity() + candidate.openingSituation()).matches(".*人族(少年|少女|男子|女子).*")));

        SaveAggregate current = saves.create(userId, new CreateSaveCommand(request.name(),
                request.genderExpression(), request.race(), request.rootType(), request.rootDescription(),
                request.lifeGoal(), request.difficulty(), "ability-spirit-object-memory",
                proposal.proposalId(), proposal.candidates().getFirst().id()));
        assertEquals("SPIRIT", current.protagonist().race().name());
        assertEquals("矿石成精", current.protagonist().rootType());
        assertTrue(current.abilities().stream().anyMatch(ability -> "物忆".equals(ability.name())));

        String[] actions = {
                "施展物忆，触碰随身旧物，分辨其中是否留有唤醒我的那道声音",
                "留在照骨矿中观察矿役交班，并谨慎询问百年前矿道异响的旧事",
                "在安全的石隙中休整一夜，整理今天得到的线索，但不尝试突破境界"
        };
        long previousWorldMinute = current.worldMinute();
        for (int index = 0; index < actions.length; index++) {
            Operation operation = submitAndAwait(new TestSave(userId, current), actions[index]);
            assertEquals("SUCCEEDED", operation.status(), "journey step " + (index + 1) + ": " + diagnostic(operation));
            current = saves.requireAggregate(current.save().id(), userId);
            assertEquals(index + 1, current.save().turnNumber());
            assertEquals(index + 1, current.save().stateVersion());
            assertTrue(current.worldMinute() > previousWorldMinute);
            assertEquals("SPIRIT", current.protagonist().race().name());
            assertEquals("矿石成精", current.protagonist().rootType());
            assertTrue(current.abilities().stream().anyMatch(ability -> "物忆".equals(ability.name())));
            previousWorldMinute = current.worldMinute();
            reportMetrics("journey-step-" + (index + 1), userId, -1);
        }
        assertEquals(3, saves.chronicle(current.save().id(), userId, 10).size());
    }

    @Test
    void platformModelCoversTheHistoricalPlayerActionMixWithoutLosingState() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("V2_LONG_MODEL_TEST")),
                "set V2_LONG_MODEL_TEST=true to run the 20-turn release journey");
        String userId = createUser("v2-real-corpus-");
        SaveAggregate seeded = saves.create(userId, new CreateSaveCommand("沈观澜", "女", "HUMAN",
                "药铺学徒", "青木镇药铺里负责晒药与记账的凡人学徒",
                "查清师父留下的残缺药方来自何处", "NORMAL", "ability-human-clear-heart"));
        seedRealModelWalletItemAndNpcs(seeded);
        TestSave test = new TestSave(userId, saves.requireAggregate(seeded.save().id(), userId));
        SaveAggregate current = test.save();
        long initialMinute = current.worldMinute();
        List<String> actions = List.of(
                "先在青木镇东门查验车辙与药渣，只记录能亲眼确认的痕迹",
                "向东门常住的陆伯询问近日运药车的来路，不替他预设回答",
                "施展已经掌握的心窍澄明，整理陆伯说法中互相矛盾的部分",
                "从行囊取出一块粗粮干饼吃下，再继续观察东门动静",
                "在东门摊贩处用三文钱买一块粗粮干饼，价格不合适就不成交",
                "按已有心得修行一个月，只温养呼吸，不尝试突破境界",
                "沿官道从青木镇东门前往青木镇内，不抄未知近路",
                "进入青木镇后查验药材集市的车印与卸货记录",
                "向镇内常住的周钺询问旧药方，只交换已经确认的消息",
                "与周钺进行一次点到即止的切磋，不拼命，也不预设胜负",
                "在青木镇安全处休整三日，让时间按实际天数推进",
                "把目前确认的线索写成一页笔记，不凭空增加新证据",
                "沿已知道路从青木镇前往回春堂，不瞬移也不离开青岐州",
                "在回春堂只询问止血草的当地价格，这次不购买",
                "检查回春堂药柜与晒场的使用痕迹，不擅自拿走药材",
                "向回春堂附近的韩青询问师父旧事，允许对方拒绝回答",
                "再次施展心窍澄明，分清亲眼所见、旁人说法和自己的猜测",
                "在回春堂附近修行三个月，巩固已有心得但不尝试突破",
                "休息一夜，检查行囊、钱包和身体状态是否与记录一致",
                "综合二十回经历，决定下一步最值得调查的方向，但不宣布结果"
        );

        for (int index = 0; index < actions.size(); index++) {
            Operation operation = submitAndAwait(new TestSave(test.userId(), current), actions.get(index));
            assertEquals("SUCCEEDED", operation.status(),
                    "long journey step " + (index + 1) + ": " + diagnostic(operation));
            current = saves.requireAggregate(current.save().id(), test.userId());
            assertEquals(index + 1, current.save().turnNumber());
            assertEquals(index + 1, current.save().stateVersion());
            assertEquals("沈观澜", current.protagonist().name());
            assertEquals("HUMAN", current.protagonist().race().name());
            assertEquals("药铺学徒", current.protagonist().rootType());
            assertTrue(current.worldMinute() > initialMinute);
            reportMetrics("long-journey-step-" + (index + 1), test.userId(), -1);
        }

        assertEquals(20, saves.chronicle(current.save().id(), test.userId(), 100).size());
        Set<String> actionTypes = new HashSet<>();
        for (String payload : jdbc.queryForList(
                "select action_intent from v2_turn where save_id=? order by turn_number",
                String.class, current.save().id())) {
            actionTypes.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload)
                    .path("actionType").asText());
        }
        assertTrue(actionTypes.containsAll(Set.of("INVESTIGATE", "INTERACT", "USE_ABILITY", "USE_ITEM",
                "TRADE", "CULTIVATE", "TRAVEL", "COMBAT", "WAIT", "OTHER")),
                "real model action coverage=" + actionTypes);
        UsageTotals totals = jdbc.query("""
                select count(*) calls,sum(prompt_tokens) prompt_tokens,
                       sum(completion_tokens) completion_tokens,avg(latency_millis) average_latency
                from v2_ai_usage_log where user_id=? and result_status='SUCCESS'
                """, (row, index) -> new UsageTotals(row.getInt("calls"), row.getLong("prompt_tokens"),
                row.getLong("completion_tokens"), row.getLong("average_latency")), test.userId()).getFirst();
        assertEquals(20, totals.calls());
        System.out.printf("V2_LONG_MODEL_TOTAL calls=%d prompt=%d completion=%d averageLatencyMs=%d%n",
                totals.calls(), totals.promptTokens(), totals.completionTokens(), totals.averageLatencyMillis());
    }

    private void seedRealModelWalletItemAndNpcs(SaveAggregate aggregate) {
        jdbc.update("update v2_wallet set balance=50 where save_id=? and currency_code='WEN'",
                aggregate.save().id());
        String inventoryId = jdbc.queryForObject(
                "select id from v2_inventory where save_id=? and owner_character_id=?",
                String.class, aggregate.save().id(), aggregate.protagonist().id());
        jdbc.update("""
                insert into v2_item_instance(id,save_id,inventory_id,item_definition_id,quantity,item_status,
                  condition_summary,provenance_summary,acquired_world_minute,state_version,updated_at)
                values (?,?,?,'item-coarse-ration',4,'AVAILABLE','干燥完好','出发前准备的普通干粮',?,0,?)
                """, "v2-item-real-corpus-" + UUID.randomUUID(), aggregate.save().id(), inventoryId,
                aggregate.worldMinute(), Timestamp.from(Instant.now()));
        seedRealNpc(aggregate, "陆伯", "loc-qingmu-east-gate", "东门常住的老车夫");
        seedRealNpc(aggregate, "周钺", "loc-qingmu-town", "青木镇内替商队记账的青年");
        seedRealNpc(aggregate, "韩青", "loc-huichun-hall", "回春堂附近采买药材的伙计");
    }

    private void seedRealNpc(SaveAggregate aggregate, String name, String locationId, String identity) {
        String npcId = "v2-npc-real-corpus-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into v2_character(id,save_id,character_kind,name,gender_expression,race,root_type,
                  root_description,life_goal,status,created_world_minute,created_at,updated_at)
                values (?,?,'NPC',?,'不设定','HUMAN','本地居民',?,'守住自己的生计与承诺','ALIVE',?,?,?)
                """, npcId, aggregate.save().id(), name, identity, aggregate.worldMinute(),
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into v2_character_state(character_id,location_id,life_stage_code,realm_code,
                  realm_display_name,realm_phase,public_identity,body_condition,current_goal,state_version,updated_at)
                values (?,?,'ADULT','MORTAL','凡俗','INITIAL',?,'安稳','继续自己的日常生活',0,?)
                """, npcId, locationId, identity, Timestamp.from(now));
    }

    private TestSave createSave(String race, String name, String rootType,
                                String rootDescription, String lifeGoal) {
        String userId = createUser("v2-real-smoke-");
        SaveAggregate save = saves.create(userId, new CreateSaveCommand(name, "不设定", race,
                rootType, rootDescription, lifeGoal, "NORMAL"));
        return new TestSave(userId, save);
    }

    private String createUser(String prefix) {
        String userId = prefix + UUID.randomUUID();
        jdbc.update("insert into app_user(id,email,status,registered_at) values (?,?,'ACTIVE',?)",
                userId, userId + "@wenxian.invalid", Timestamp.from(Instant.now()));
        return userId;
    }

    private Operation submitAndAwait(TestSave test, String action) throws InterruptedException {
        long started = System.nanoTime();
        Operation operation = turns.submit(test.userId(), test.save().save().id(),
                new SubmitActionCommand("real-smoke-" + UUID.randomUUID(),
                        test.save().save().stateVersion(), "FREE", action, null, null));
        while (!terminal(operation.status()) && (System.nanoTime() - started) < 90_000_000_000L) {
            Thread.sleep(200);
            operation = turns.requireOperation(test.userId(), operation.id());
        }
        return operation;
    }

    private void reportMetrics(String scenario, String userId, long wallMillis) {

        Metrics metrics = jdbc.query("""
                select prompt_tokens,completion_tokens,latency_millis,result_status,model_code
                from v2_ai_usage_log where user_id=? order by created_at desc limit 1
                """, (row, index) -> new Metrics(row.getInt("prompt_tokens"),
                row.getInt("completion_tokens"), row.getLong("latency_millis"),
                row.getString("result_status"), row.getString("model_code")), userId).getFirst();
        assertEquals("SUCCESS", metrics.status());
        System.out.printf("V2_REAL_MODEL_METRIC scenario=%s model=%s prompt=%s completion=%s latencyMs=%s wallMs=%d%n",
                scenario, metrics.model(), metrics.promptTokens(), metrics.completionTokens(),
                metrics.latencyMillis(), wallMillis);
    }

    private boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "REJECTED".equals(status) || "FAILED".equals(status);
    }

    private String diagnostic(Operation operation) {
        return "%s | %s | %s".formatted(operation.errorCode(), operation.errorMessage(),
                String.join("；", operation.errorSuggestions()));
    }

    private record Metrics(int promptTokens, int completionTokens, long latencyMillis,
                           String status, String model) {
    }

    private record UsageTotals(int calls, long promptTokens, long completionTokens,
                               long averageLatencyMillis) {
    }

    private record TestSave(String userId, SaveAggregate save) {
    }
}
