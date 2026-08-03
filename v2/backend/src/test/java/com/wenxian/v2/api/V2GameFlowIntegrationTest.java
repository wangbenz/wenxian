package com.wenxian.v2.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.application.TurnProposalGenerator;
import com.wenxian.v2.ai.application.ModelGateway;
import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.TurnProposal;
import com.wenxian.v2.ai.domain.TurnProposal.ConfirmedFactProposal;
import com.wenxian.v2.ai.domain.TurnProposal.DomainEffectProposal;
import com.wenxian.v2.ai.domain.TurnProposal.Generated;
import com.wenxian.v2.ai.domain.TurnProposal.AbilityLearningProposal;
import com.wenxian.v2.ai.domain.TurnProposal.BreakthroughProposal;
import com.wenxian.v2.ai.domain.TurnProposal.CharacterIntroductionProposal;
import com.wenxian.v2.ai.domain.TurnProposal.ConflictProposal;
import com.wenxian.v2.ai.domain.TurnProposal.HighImpactProposal;
import com.wenxian.v2.ai.domain.TurnProposal.LifeEndingProposal;
import com.wenxian.v2.ai.domain.TurnProposal.TurnChoice;
import com.wenxian.v2.ai.domain.ModelContracts.ModelResult;
import com.wenxian.v2.game.domain.WorldCalendar;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class V2GameFlowIntegrationTest {
    private static final String SESSION_COOKIE = "WENXIAN_SESSION";

    @Autowired
    MockMvc http;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    TurnProposalGenerator proposals;

    @MockitoBean
    ModelGateway models;

    private Cookie session;

    @BeforeEach
    void createSharedAccountSession() throws Exception {
        String userId = "http-flow-user-" + UUID.randomUUID();
        String rawToken = "http-flow-token-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("insert into app_user(id,email,status,registered_at) values (?,?,'ACTIVE',?)",
                userId, userId + "@example.invalid", Timestamp.from(now));
        jdbc.update("""
                insert into user_session(id,user_id,token_hash,expires_at,created_at)
                values (?,?,?,?,?)
                """, "http-session-" + UUID.randomUUID(), userId, sha256(rawToken),
                Timestamp.from(now.plus(Duration.ofDays(1))), Timestamp.from(now));
        session = new Cookie(SESSION_COOKIE, rawToken);
    }

    @Test
    void completesTheFrontendContractFromBootstrapToPersistedTurn() throws Exception {
        http.perform(get("/api/v2/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineVersion").value("0.2.0-test"))
                .andExpect(jsonPath("$.maxActiveSaves").value(2))
                .andExpect(jsonPath("$.races[0]").value("HUMAN"))
                .andExpect(jsonPath("$.deterministicActions[0]").value("WAIT"));

        MvcResult createdResponse = http.perform(post("/api/v2/saves")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"照微",
                                  "genderExpression":"女",
                                  "race":"SPIRIT",
                                  "rootType":"矿灵",
                                  "rootDescription":"照骨矿深处听了百年镐声的青黑矿石",
                                  "lifeGoal":"离开矿脉，弄清自己为何记得矿工的名字",
                                  "difficulty":"NORMAL"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protagonist.name").value("照微"))
                .andExpect(jsonPath("$.protagonist.race").value("SPIRIT"))
                .andExpect(jsonPath("$.characterState.realmDisplayName").value("萌灵"))
                .andExpect(jsonPath("$.inventory[0].itemCode").value("IDENTITY_TOKEN"))
                .andReturn();

        JsonNode created = json.readTree(createdResponse.getResponse().getContentAsByteArray());
        String saveId = created.path("save").path("id").asText();
        long initialWorldMinute = created.path("worldMinute").asLong();

        http.perform(get("/api/v2/saves").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].save.id").value(saveId));
        http.perform(get("/api/v2/saves/{saveId}/world", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='QINGQI')].id").exists())
                .andExpect(jsonPath("$[?(@.code=='QINGMU_TOWN')].directTravelMinutes").value(240));
        http.perform(get("/api/v2/saves/{saveId}/chronicle", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String requestId = "http-action-" + UUID.randomUUID();
        MvcResult submittedResponse = http.perform(post("/api/v2/saves/{saveId}/actions", saveId)
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId":"%s",
                                  "expectedStateVersion":0,
                                  "actionType":"WAIT",
                                  "actionText":"留在矿道静候一个月",
                                  "durationMinutes":43200
                                }
                                """.formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andReturn();
        String operationId = json.readTree(submittedResponse.getResponse().getContentAsByteArray())
                .path("id").asText();

        JsonNode operation = awaitOperation(operationId);
        assertThat(operation.path("status").asText()).isEqualTo("SUCCEEDED");

        MvcResult eventStream = http.perform(get("/api/v2/turn-operations/{operationId}/events", operationId)
                        .cookie(session))
                .andExpect(request().asyncStarted())
                .andReturn();
        http.perform(asyncDispatch(eventStream))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"SUCCEEDED\"")));

        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.save.turnNumber").value(1))
                .andExpect(jsonPath("$.save.stateVersion").value(1))
                .andExpect(jsonPath("$.worldMinute").value(initialWorldMinute + 43_200));
        http.perform(get("/api/v2/saves/{saveId}/chronicle", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].turnNumber").value(1))
                .andExpect(jsonPath("$[0].playerAction").value("留在矿道静候一个月"));

        http.perform(post("/api/v2/saves/{saveId}/actions", saveId)
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId":"stale-%s",
                                  "expectedStateVersion":0,
                                  "actionType":"WAIT",
                                  "actionText":"继续使用旧页面行动",
                                  "durationMinutes":60
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_VERSION_CONFLICT"));
    }

    @Test
    void rejectsGameDataWithoutTheSharedLoginCookie() throws Exception {
        http.perform(get("/api/v2/saves"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void resolvesAFreeActionWithOneModelProposalAndPersistsChoicesAndFacts() throws Exception {
        JsonNode created = createSave("拾砾", "SPIRIT", "矿灵", "记住照骨矿里消失的声音");
        String saveId = created.path("save").path("id").asText();
        long initialMinute = created.path("worldMinute").asLong();
        String protagonistId = created.path("protagonist").path("id").asText();
        String narrative = """
                你没有立刻挤进裂缝，而是把意识贴近潮湿的石面。矿道里的镐声一阵阵远去，细小水流从岩隙间渗出，带来不同于矿尘的草木气。

                等巡夜矿役的灯火转过弯角，你才沿着水痕摸索。旧排水渠并未完全坍塌，深处留着拖拽重物的浅痕，却没有足够证据说明是谁来过。你记住了痕迹朝向，退回仍能看见来路的位置。

                这次探查没有让你直接离开照骨矿，但确认了矿道深处另有一条尚未完全堵死的旧渠。远处换班的木梆响起，你必须决定是继续冒险，还是先查清巡夜规律。
                """;
        ActionIntent intent = new ActionIntent("INVESTIGATE", "ATTEMPT", null, null, null,
                List.of(), List.of(), null, false, List.of(), "调查旧排水渠");
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(intent, 120L,
                "INFORMATION_DISCOVERED", "确认矿道深处存在尚未完全堵死的旧排水渠", narrative,
                List.of(new ConfirmedFactProposal("LOCATION", "loc-zhaogu-mine",
                        "照骨矿深处存在一条尚未完全堵死的旧排水渠")),
                List.of(new TurnChoice("继续查看拖痕", "等换班后沿旧渠查看拖痕的去向"),
                        new TurnChoice("先查巡夜规律", "退回安全处记录巡夜矿役的换班规律"))),
                "DEEPSEEK", "deepseek-v4-flash"));

        MvcResult submitted = http.perform(post("/api/v2/saves/{saveId}/actions", saveId)
                        .cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"free-%s","expectedStateVersion":0,"actionType":"FREE",
                                 "actionText":"先观察旧排水渠，不贸然深入"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn();
        String operationId = json.readTree(submitted.getResponse().getContentAsByteArray()).path("id").asText();
        assertThat(awaitOperation(operationId).path("status").asText()).isEqualTo("SUCCEEDED");

        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.save.turnNumber").value(1))
                .andExpect(jsonPath("$.worldMinute").value(initialMinute + 120));
        http.perform(get("/api/v2/saves/{saveId}/chronicle", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].choices[0].title").value("继续查看拖痕"))
                .andExpect(jsonPath("$[0].choices[1].actionText").value("退回安全处记录巡夜矿役的换班规律"));
        Integer factCount = jdbc.queryForObject("""
                select count(*) from v2_domain_change where save_id=? and entity_id=?
                  and change_type='NARRATIVE_FACT_CONFIRMED'
                """, Integer.class, saveId, "loc-zhaogu-mine");
        assertThat(factCount).isEqualTo(1);
        assertThat(jdbc.queryForObject("select model_code from v2_turn where save_id=?", String.class, saveId))
                .isEqualTo("deepseek-v4-flash");
        assertThat(protagonistId).isNotBlank();
    }

    @Test
    void rejectsDeclaredBreakthroughWithoutConsumingATurnAndKeepsSuggestions() throws Exception {
        JsonNode created = createSave("陆微", "HUMAN", "流民", "在青木镇寻找安身之处");
        String saveId = created.path("save").path("id").asText();
        long initialMinute = created.path("worldMinute").asLong();
        ActionIntent declared = new ActionIntent("CULTIVATE", "DECLARE_OUTCOME", null, null, null,
                List.of(), List.of(), "GOLDEN_CORE", true, List.of(), "直接突破金丹");
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(declared, 60L,
                "ACTION_SUCCEEDED", "境界骤然突破", "这段文本不会进入正式命卷，因为行动会先经过Java裁决。".repeat(5),
                List.of(), List.of()), "DEEPSEEK", "deepseek-v4-flash"));

        MvcResult submitted = http.perform(post("/api/v2/saves/{saveId}/actions", saveId)
                        .cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"realm-%s","expectedStateVersion":0,"actionType":"FREE",
                                 "actionText":"我立刻突破金丹"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn();
        String operationId = json.readTree(submitted.getResponse().getContentAsByteArray()).path("id").asText();
        JsonNode operation = awaitOperation(operationId);

        assertThat(operation.path("status").asText()).isEqualTo("REJECTED");
        assertThat(operation.path("errorCode").asText()).isEqualTo("OUTCOME_CANNOT_BE_DECLARED");
        assertThat(operation.path("errorSuggestions").get(0).asText()).contains("尝试");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.save.turnNumber").value(0))
                .andExpect(jsonPath("$.worldMinute").value(initialMinute));
    }

    @Test
    void honorsAnExplicitThreeYearCultivationDurationWithoutGrantingARealm() throws Exception {
        JsonNode created = createSave("青禾", "HUMAN", "药农", "寻得一条可以自证的修行道路");
        String saveId = created.path("save").path("id").asText();
        String protagonistId = created.path("protagonist").path("id").asText();
        long initialMinute = created.path("worldMinute").asLong();
        long threeYears = 3L * WorldCalendar.MINUTES_PER_YEAR;
        ActionIntent cultivate = new ActionIntent("CULTIVATE", "ATTEMPT", threeYears, null, null,
                List.of(), List.of(), null, false, List.of(), "闭关梳理吐纳所得");
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(cultivate, threeYears,
                "ACTION_PROGRESSED", "三年闭关使吐纳更加稳定，但尚未形成新的境界",
                "你在青岐州寻得一处不受山风侵扰的石室，将每日吐纳、饮食和身体变化逐一记下。".repeat(6),
                List.of(), List.of(new DomainEffectProposal("CULTIVATION_INSIGHT", protagonistId,
                        null, null, null, "吐纳需要顺应昼夜寒温，不可只求延长行气时间",
                        "三年反复验证后确认的修行心得")),
                List.of(new TurnChoice("结束闭关", "整理三年所得并离开石室"),
                        new TurnChoice("寻找传承", "带着吐纳记录去青木镇寻找懂行之人"))),
                "DEEPSEEK", "deepseek-v4-flash"));

        MvcResult submitted = http.perform(post("/api/v2/saves/{saveId}/actions", saveId)
                        .cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"years-%s","expectedStateVersion":0,"actionType":"FREE",
                                 "actionText":"闭关三年，梳理已有的吐纳心得"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn();
        String operationId = json.readTree(submitted.getResponse().getContentAsByteArray()).path("id").asText();
        assertThat(awaitOperation(operationId).path("status").asText()).isEqualTo("SUCCEEDED");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldMinute").value(initialMinute + threeYears))
                .andExpect(jsonPath("$.characterState.realmDisplayName").value("凡俗"))
                .andExpect(jsonPath("$.cultivation.projectStage").value("PRACTICING"))
                .andExpect(jsonPath("$.cultivation.insightSummary")
                        .value("吐纳需要顺应昼夜寒温，不可只求延长行气时间"));
        http.perform(get("/api/v2/saves/{saveId}/chronicle", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changes[?(@.changeType=='CULTIVATION_INSIGHT')]").exists());
    }

    @Test
    void advancesOnlyTheKnownNextWorldEventStageAndProjectsItToTheWorldPage() throws Exception {
        JsonNode created = createSave("闻砧", "SPIRIT", "矿灵", "追查照骨矿深处的闷响");
        String saveId = created.path("save").path("id").asText();
        String eventId = created.path("worldEvents").get(0).path("id").asText();
        ActionIntent investigate = new ActionIntent("INVESTIGATE", "ATTEMPT", null, null, null,
                List.of(), List.of(), null, false, List.of(), "核对矿工所说的闷响");
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(investigate, 120L,
                "INFORMATION_DISCOVERED", "多名矿工在同一时辰听见深处闷响",
                "你没有贸然进入封闭矿道，而是分开询问三名刚换班的矿工。三人的说法互不相同，唯独闷响出现的时辰完全一致。".repeat(4),
                List.of(), List.of(new DomainEffectProposal("EVENT_ADVANCED", eventId,
                        null, null, null, "SIGN", "闷响由零散传闻变成可以互相印证的征兆")),
                List.of(new TurnChoice("查看矿图", "寻找旧矿图核对闷响方向"),
                        new TurnChoice("暂时离开", "记下证词后先离开矿区"))),
                "DEEPSEEK", "deepseek-v4-flash"));

        String operationId = submitFree(saveId, 0, "分别询问矿工，核对闷响出现的时辰");
        assertThat(awaitOperation(operationId).path("status").asText()).isEqualTo("SUCCEEDED");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldEvents[0].id").value(eventId))
                .andExpect(jsonPath("$.worldEvents[0].stage").value("SIGN"))
                .andExpect(jsonPath("$.worldEvents[0].publicSummary")
                        .value("闷响由零散传闻变成可以互相印证的征兆"));
        http.perform(get("/api/v2/saves/{saveId}/chronicle", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changes[?(@.changeType=='EVENT_ADVANCED')]").exists());
    }

    @Test
    void commitsValidatedItemAndBodyEffectsAndReturnsThemInTheTurnReceipt() throws Exception {
        JsonNode created = createSave("阿芜", "HUMAN", "流民", "先在青木镇活下来");
        String saveId = created.path("save").path("id").asText();
        String protagonistId = created.path("protagonist").path("id").asText();
        String inventoryId = jdbc.queryForObject(
                "select id from v2_inventory where save_id=? and owner_character_id=?",
                String.class, saveId, protagonistId);
        String itemId = "ration-" + UUID.randomUUID();
        jdbc.update("""
                insert into v2_item_instance(id,save_id,inventory_id,item_definition_id,quantity,item_status,
                  condition_summary,provenance_summary,acquired_world_minute,state_version,updated_at)
                values (?,?,?,'item-coarse-ration',2,'AVAILABLE','尚可','入城前换来的干粮',0,0,?)
                """, itemId, saveId, inventoryId, Timestamp.from(Instant.now()));

        ActionIntent intent = new ActionIntent("USE_ITEM", "EXECUTE", null, null, null,
                List.of(itemId), List.of(), null, false, List.of(), "吃下一块粗粮干饼");
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(intent, 30L,
                "ACTION_SUCCEEDED", "吃下干饼后，饥饿稍有缓解",
                "你在避风的墙根坐下，把粗硬的干饼一点点咽下。凉水压住喉间的干涩，空荡的胃终于不再抽紧。".repeat(4),
                List.of(), List.of(
                        new DomainEffectProposal("ITEM_CONSUMED", itemId, null, null,
                                BigDecimal.ONE, null, "吃下一块粗粮干饼"),
                        new DomainEffectProposal("BODY_CONDITION_SET", protagonistId, null, null,
                                null, "饥饿稍缓，仍有些疲惫", "进食后身体状态有所恢复")),
                List.of(new TurnChoice("继续赶路", "起身沿街寻找可以落脚的地方"),
                        new TurnChoice("暂时歇息", "留在避风处再休息片刻"))),
                "DEEPSEEK", "deepseek-v4-flash"));

        String operationId = submitFree(saveId, 0, "吃下一块粗粮干饼");
        assertThat(awaitOperation(operationId).path("status").asText()).isEqualTo("SUCCEEDED");

        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventory[?(@.instanceId=='" + itemId + "')].quantity").value(1.0))
                .andExpect(jsonPath("$.characterState.bodyCondition").value("饥饿稍缓，仍有些疲惫"));
        http.perform(get("/api/v2/saves/{saveId}/chronicle", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].changes[?(@.changeType=='ITEM_CONSUMED')]").exists())
                .andExpect(jsonPath("$[0].changes[?(@.changeType=='BODY_CONDITION_SET')]").exists());
    }

    @Test
    void commitsTradePaymentAndItemAcquisitionAtomically() throws Exception {
        JsonNode created = createSave("林照", "HUMAN", "商旅遗孤", "在青木镇立足");
        String saveId = created.path("save").path("id").asText();
        String protagonistId = created.path("protagonist").path("id").asText();
        jdbc.update("update v2_wallet set balance=20 where save_id=? and owner_character_id=? and currency_code='WEN'",
                saveId, protagonistId);

        ActionIntent intent = new ActionIntent("TRADE", "ATTEMPT", null, null, null,
                List.of(), List.of(), null, false, List.of(), "买两块粗粮干饼");
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(intent, 20L,
                "ACTION_SUCCEEDED", "用六文钱买到两块粗粮干饼",
                "摊主把两块烤得发硬的干饼用旧纸包好。你数出六枚铜钱递过去，确认没有短缺后才把纸包收入行囊。".repeat(4),
                List.of(), List.of(
                        new DomainEffectProposal("WALLET_DEBIT", null, null, "WEN",
                                new BigDecimal("6"), null, "买两块粗粮干饼"),
                        new DomainEffectProposal("ITEM_ACQUIRED", null, "item-coarse-ration", null,
                                new BigDecimal("2"), null, "在青木镇摊位买到两块粗粮干饼")),
                List.of(new TurnChoice("询问行情", "顺势询问最近粮价为何上涨"),
                        new TurnChoice("带粮离开", "收好干饼，离开摊位继续办事"))),
                "DEEPSEEK", "deepseek-v4-flash"));

        String operationId = submitFree(saveId, 0, "花六文钱买两块粗粮干饼");
        assertThat(awaitOperation(operationId).path("status").asText()).isEqualTo("SUCCEEDED");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallet[?(@.currencyCode=='WEN')].balance").value(14.0))
                .andExpect(jsonPath("$.inventory[?(@.itemCode=='COARSE_RATION')].quantity").value(2.0));
        assertThat(jdbc.queryForObject("select count(*) from v2_wallet_ledger where save_id=?",
                Integer.class, saveId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from v2_item_event where save_id=? and event_type='ACQUIRED'",
                Integer.class, saveId)).isEqualTo(2);
    }

    @Test
    void rejectsAnInvalidItemEffectWithoutConsumingATurn() throws Exception {
        JsonNode created = createSave("石生", "SPIRIT", "石灵", "守住自己的来历");
        String saveId = created.path("save").path("id").asText();
        String keepsakeId = created.path("inventory").get(0).path("instanceId").asText();
        long initialMinute = created.path("worldMinute").asLong();
        ActionIntent intent = new ActionIntent("USE_ITEM", "EXECUTE", null, null, null,
                List.of(keepsakeId), List.of(), null, false, List.of(), "吞下身世旧物");
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(intent, 30L,
                "ACTION_SUCCEEDED", "吞下旧物",
                "这段结果不应写入命卷，因为身世旧物并不是可以被消耗的普通物品。".repeat(6),
                List.of(), List.of(new DomainEffectProposal("ITEM_CONSUMED", keepsakeId, null, null,
                        BigDecimal.ONE, null, "吞下身世旧物")), List.of()),
                "DEEPSEEK", "deepseek-v4-flash"));

        String operationId = submitFree(saveId, 0, "吞下身世旧物");
        JsonNode operation = awaitOperation(operationId);
        assertThat(operation.path("status").asText()).isEqualTo("REJECTED");
        assertThat(operation.path("errorCode").asText()).isEqualTo("AI_DOMAIN_EFFECT_INVALID");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.save.turnNumber").value(0))
                .andExpect(jsonPath("$.worldMinute").value(initialMinute))
                .andExpect(jsonPath("$.inventory[0].quantity").value(1.0));
    }

    @Test
    void rejectsAnOutOfRangeTradePriceAndRollsBackTheWholeTurn() throws Exception {
        JsonNode created = createSave("程禾", "HUMAN", "脚夫", "攒下离开青木镇的路费");
        String saveId = created.path("save").path("id").asText();
        String protagonistId = created.path("protagonist").path("id").asText();
        long initialMinute = created.path("worldMinute").asLong();
        jdbc.update("update v2_wallet set balance=20 where save_id=? and owner_character_id=? and currency_code='WEN'",
                saveId, protagonistId);
        ActionIntent trade = new ActionIntent("TRADE", "ATTEMPT", null, null, null,
                List.of(), List.of(), null, false, List.of(), "用一文钱买一块粗粮干饼");
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(trade, 20L,
                "ACTION_SUCCEEDED", "用一文钱买到粗粮干饼",
                "摊主接过一枚铜钱，把一块干饼递到你手中。这段结果不应进入正式命卷，因为价格低于青木镇已经确认的物价下限。".repeat(4),
                List.of(), List.of(
                        new DomainEffectProposal("WALLET_DEBIT", null, null, "WEN",
                                BigDecimal.ONE, null, "购买一块粗粮干饼"),
                        new DomainEffectProposal("ITEM_ACQUIRED", null, "item-coarse-ration", null,
                                BigDecimal.ONE, null, "在青木镇摊位买到一块粗粮干饼")), List.of()),
                "DEEPSEEK", "deepseek-v4-flash"));

        String operationId = submitFree(saveId, 0, "用一文钱买一块粗粮干饼");
        JsonNode operation = awaitOperation(operationId);
        assertThat(operation.path("status").asText()).isEqualTo("REJECTED");
        assertThat(operation.path("errorCode").asText()).isEqualTo("AI_DOMAIN_EFFECT_INVALID");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.save.turnNumber").value(0))
                .andExpect(jsonPath("$.worldMinute").value(initialMinute))
                .andExpect(jsonPath("$.wallet[?(@.currencyCode=='WEN')].balance").value(20.0))
                .andExpect(jsonPath("$.inventory[?(@.itemCode=='COARSE_RATION')]").doesNotExist());
    }

    @Test
    void persistsANamedNpcRelationshipAndLearnedAbilityAsAuthoritativeState() throws Exception {
        JsonNode created = createSave("陆归", "HUMAN", "采药人", "在青木镇找到可靠的修行引路人");
        String saveId = created.path("save").path("id").asText();
        ActionIntent intent = new ActionIntent("INTERACT", "ATTEMPT", null, null, null,
                List.of(), List.of(), null, false, List.of(), "请教老猎户辨认山道痕迹");
        HighImpactProposal highImpact = new HighImpactProposal(List.of(
                new CharacterIntroductionProposal("NEW_NPC_1", "顾七", "男", "HUMAN", "山民",
                        "常年替青木镇商队探路的老猎户，右手少了一截小指。",
                        "攒够银钱后离开北境驿道", "青木镇的老猎户", "MENTOR")),
                List.of(), List.of(new AbilityLearningProposal("ability-human-trace-reading",
                "NEW_NPC_1", "顾七让你反复分辨三组新旧脚印，确认你已能独立判断")),
                null, null, null);
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(intent, 90L,
                "ACTION_SUCCEEDED", "顾七教会你辨认最基础的山道痕迹",
                "你在驿道边找到顾七时，他没有立刻答应。直到你把自己误认的兽迹一一指出，他才蹲下身，用断了一截小指的右手拨开浮土。".repeat(4),
                List.of(), List.of(), highImpact,
                List.of(new TurnChoice("独自练习", "沿驿道寻找三组脚印，独自判断来去方向"))),
                "DEEPSEEK", "deepseek-v4-flash"));

        String operationId = submitFree(saveId, 0, "请顾七教我辨认山道上的新旧脚印");
        assertThat(awaitOperation(operationId).path("status").asText()).isEqualTo("SUCCEEDED");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knownCharacters[0].name").value("顾七"))
                .andExpect(jsonPath("$.knownCharacters[0].publicIdentity").value("青木镇的老猎户"))
                .andExpect(jsonPath("$.relationships[0].targetName").value("顾七"))
                .andExpect(jsonPath("$.relationships[0].relationshipType").value("MENTOR"))
                .andExpect(jsonPath("$.abilities[0].name").value("察迹"))
                .andExpect(jsonPath("$.abilities[0].masteryStage").value("初识"));
        assertThat(jdbc.queryForObject("""
                select count(*) from v2_domain_change where save_id=?
                  and change_type in ('CHARACTER_INTRODUCED','RELATIONSHIP_ESTABLISHED','ABILITY_LEARNED')
                """, Integer.class, saveId)).isEqualTo(3);

        MvcResult waited = http.perform(post("/api/v2/saves/{saveId}/actions", saveId)
                        .cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"npc-time-%s","expectedStateVersion":1,"actionType":"WAIT",
                                 "actionText":"留在青木镇等待三十一日","durationMinutes":44640}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn();
        String waitOperationId = json.readTree(waited.getResponse().getContentAsByteArray()).path("id").asText();
        assertThat(awaitOperation(waitOperationId).path("status").asText()).isEqualTo("SUCCEEDED");

        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knownCharacters[0].currentGoal")
                        .value("正在推进：攒够银钱后离开北境驿道"));
        assertThat(jdbc.queryForObject("""
                select count(*) from v2_schedule where save_id=? and status='RESOLVED'
                """, Integer.class, saveId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from v2_schedule where save_id=? and status='PENDING'
                  and schedule_type='NPC_GOAL_ADVANCED'
                """, Integer.class, saveId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from v2_domain_change where save_id=? and change_type='NPC_GOAL_ADVANCED'
                """, Integer.class, saveId)).isEqualTo(1);
    }

    @Test
    void allowsOnlyAPreparedAdjacentBreakthroughAndPersistsBothRealmProjections() throws Exception {
        JsonNode created = createSave("沈砚", "HUMAN", "书吏", "验证凡人是否真能引气入体");
        String saveId = created.path("save").path("id").asText();
        String protagonistId = created.path("protagonist").path("id").asText();
        jdbc.update("""
                update v2_cultivation set project_stage='PRACTICING',insight_summary='已能在晨昏时分辨认灵机流向'
                where save_id=? and character_id=?
                """, saveId, protagonistId);
        ActionIntent intent = new ActionIntent("CULTIVATE", "ATTEMPT", null, null, null,
                List.of(), List.of(), "QI_DRAWING", false, List.of(), "尝试完成第一次引气");
        HighImpactProposal highImpact = new HighImpactProposal(List.of(), List.of(), List.of(),
                new BreakthroughProposal("QI_DRAWING", "循着已经验证的晨昏灵机完成第一次完整周天"),
                null, null);
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(intent, 360L,
                "ACTION_SUCCEEDED", "第一次完整周天结束后，气机终于留在经脉之中",
                "你没有追逐那缕忽明忽暗的凉意，只按过去数日记下的晨昏规律调整呼吸。第三次行至胸腹之间时，散去的灵机终于没有立刻消失。".repeat(4),
                List.of(), List.of(), highImpact,
                List.of(new TurnChoice("稳固气机", "停止继续冲击，先用数日稳固刚刚留下的气机"))),
                "DEEPSEEK", "deepseek-v4-flash"));

        String operationId = submitFree(saveId, 0, "依照已有心得，尝试完成第一次引气周天");
        assertThat(awaitOperation(operationId).path("status").asText()).isEqualTo("SUCCEEDED");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.characterState.realmCode").value("QI_DRAWING"))
                .andExpect(jsonPath("$.characterState.realmDisplayName").value("引气"))
                .andExpect(jsonPath("$.cultivation.currentRealmCode").value("QI_DRAWING"))
                .andExpect(jsonPath("$.cultivation.projectStage").value("DORMANT"));
    }

    @Test
    void endsTheSaveOnlyAfterAValidatedCombatDefeat() throws Exception {
        JsonNode created = createSave("桑遥", "DEMON", "狐妖", "穿过北境驿道寻找同族");
        String saveId = created.path("save").path("id").asText();
        long minute = created.path("worldMinute").asLong();
        String npcId = "v2-npc-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into v2_character(id,save_id,character_kind,name,gender_expression,race,root_type,
                    root_description,life_goal,status,created_world_minute,created_at,updated_at)
                values (?,?,'NPC','断牙','男','DEMON','狼妖','盘踞驿道的成年狼妖','守住自己的猎场','ALIVE',?,?,?)
                """, npcId, saveId, minute, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into v2_character_state(character_id,location_id,life_stage_code,realm_code,
                    realm_display_name,realm_phase,public_identity,body_condition,current_goal,state_version,updated_at)
                values (?,'loc-northern-road','ADULT','AWAKENED','开智','INITIAL','驿道狼妖','状态尚稳','驱逐闯入者',0,?)
                """, npcId, Timestamp.from(now));
        ActionIntent intent = new ActionIntent("COMBAT", "ATTEMPT", null, null, npcId,
                List.of(), List.of(), null, false, List.of(), "试图从断牙手中突围");
        HighImpactProposal highImpact = new HighImpactProposal(List.of(), List.of(), List.of(), null,
                new ConflictProposal(npcId, "DEFEAT", "重伤后仍拒绝退让，最终未能脱离狼妖的追杀"),
                new LifeEndingProposal("伤重力竭于北境驿道",
                        "她没能找到同族，留下的足迹却让后来经过的旅人绕开了狼妖猎场。"));
        when(proposals.generate(any())).thenReturn(new Generated(new TurnProposal(intent, 60L,
                "ACTION_FAILED", "你未能从断牙的猎场中活着离开",
                "断牙第二次扑来时，你已经听不清远处的风声。你用尽最后的力气向驿道外侧跃去，却只在冻土上留下几道断续的爪痕。".repeat(4),
                List.of(), List.of(), highImpact, List.of()), "DEEPSEEK", "deepseek-v4-flash"));

        String operationId = submitFree(saveId, 0, "和断牙拼命，试图强行冲出他的猎场");
        assertThat(awaitOperation(operationId).path("status").asText()).isEqualTo("SUCCEEDED");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.save.status").value("ENDED"))
                .andExpect(jsonPath("$.protagonist.status").value("DEAD"))
                .andExpect(jsonPath("$.characterState.bodyCondition").value("已故：伤重力竭于北境驿道"));
    }

    @Test
    void createsAPersistedRetirementEndingOnlyAfterExplicitConfirmation() throws Exception {
        JsonNode created = createSave("徐隐", "HUMAN", "行脚医", "走遍青岐州后寻一处安身地");
        String saveId = created.path("save").path("id").asText();
        jdbc.update("update v2_game_save set turn_number=5,state_version=5 where id=?", saveId);

        http.perform(post("/api/v2/saves/{saveId}/end", saveId).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"旁人\",\"endingType\":\"RETIREMENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENDING_CONFIRMATION_MISMATCH"));

        http.perform(post("/api/v2/saves/{saveId}/end", saveId).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"徐隐\",\"endingType\":\"RETIREMENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.save.status").value("ENDED"))
                .andExpect(jsonPath("$.save.endReason").value("RETIREMENT"))
                .andExpect(jsonPath("$.protagonist.status").value("RETIRED"))
                .andExpect(jsonPath("$.ending.endingType").value("RETIREMENT"))
                .andExpect(jsonPath("$.ending.title").value("徐隐归隐录"))
                .andExpect(jsonPath("$.ending.finalTurnNumber").value(5));
        assertThat(jdbc.queryForObject("select count(*) from v2_life_ending where save_id=?",
                Integer.class, saveId)).isEqualTo(1);
    }

    @Test
    void requiresASeasonedLifeBeforeLeavingALegacy() throws Exception {
        JsonNode created = createSave("谢传", "SPIRIT", "碑灵", "记下青岐州无名者的来处");
        String saveId = created.path("save").path("id").asText();
        jdbc.update("update v2_game_save set turn_number=8,state_version=8 where id=?", saveId);

        http.perform(post("/api/v2/saves/{saveId}/end", saveId).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"谢传\",\"endingType\":\"LEGACY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_NOT_ESTABLISHED"));
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.save.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ending").value(nullValue()));
    }

    @Test
    void generatesThreeServerHeldFatesAndPersistsTheChosenOriginAndTalent() throws Exception {
        when(models.completeJson(any())).thenReturn(new ModelResult("""
                {"candidates":[
                  {"title":"石隙闻声","publicIdentity":"照骨矿废坑里苏醒的矿灵","openingSituation":"矿工即将重开废坑，你在岩壁中听见一个熟悉名字，却还无法判断那人是否仍然活着。","unpaidDebt":"找到曾替本体遮掩灵光的老矿工"},
                  {"title":"随车离矿","publicIdentity":"被误装进矿车的青黑矿石","openingSituation":"运矿车已经驶向青木镇，你能感到车底禁纹正在收紧，必须决定何时显露自己的意识。","unpaidDebt":"偿还同车少年替你掩住裂纹的情分"},
                  {"title":"守井残灵","publicIdentity":"守在封井木牌后的初醒灵物","openingSituation":"封井木牌将在天亮后被焚毁，而你的灵识仍与它残留的一枚铁钉相连。","unpaidDebt":"查清封井前最后一名失踪矿工的去向"}
                ]}
                """, "DEEPSEEK", "deepseek-v4-flash", "origin-request", 300, 260, 800));

        MvcResult generated = http.perform(post("/api/v2/creation/fates").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"照微","genderExpression":"不设定","race":"SPIRIT",
                                 "rootType":"矿石生灵","rootDescription":"照骨矿中听了百年镐声的青黑矿石",
                                 "lifeGoal":"离开矿脉并记住失踪矿工的名字","difficulty":"NORMAL"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(3))
                .andReturn();
        JsonNode proposal = json.readTree(generated.getResponse().getContentAsByteArray());

        http.perform(post("/api/v2/saves").cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"照微","genderExpression":"不设定","race":"SPIRIT",
                                 "rootType":"矿石生灵","rootDescription":"照骨矿中听了百年镐声的青黑矿石",
                                 "lifeGoal":"离开矿脉并记住失踪矿工的名字","difficulty":"NORMAL",
                                 "originProposalId":"%s","fateCandidateId":"fate-2",
                                 "starterTalentDefinitionId":"ability-spirit-object-memory"}
                                """.formatted(proposal.path("proposalId").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin.fateTitle").value("随车离矿"))
                .andExpect(jsonPath("$.characterState.publicIdentity").value("被误装进矿车的青黑矿石"))
                .andExpect(jsonPath("$.abilities[0].name").value("物忆"))
                .andExpect(jsonPath("$.abilities[0].sourceType").value("ORIGIN"));
    }

    @Test
    void advancesRegionalEventsWhenThePlayerLetsADeadlinePass() throws Exception {
        JsonNode created = createSave("闻砧", "SPIRIT", "矿灵", "在矿乱扩大前离开旧坑");
        String saveId = created.path("save").path("id").asText();
        long duration = 31L * WorldCalendar.MINUTES_PER_DAY;
        MvcResult submitted = http.perform(post("/api/v2/saves/{saveId}/actions", saveId)
                        .cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"world-%s","expectedStateVersion":0,"actionType":"WAIT",
                                 "actionText":"在废坑中静候三十一日","durationMinutes":%d}
                                """.formatted(UUID.randomUUID(), duration)))
                .andExpect(status().isOk()).andReturn();
        String operationId = json.readTree(submitted.getResponse().getContentAsByteArray()).path("id").asText();
        assertThat(awaitOperation(operationId).path("status").asText()).isEqualTo("SUCCEEDED");
        http.perform(get("/api/v2/saves/{saveId}", saveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.worldEvents[?(@.title=='照骨矿异动')].stage").value("SIGN"))
                .andExpect(jsonPath("$.worldEvents[?(@.title=='照骨矿异动')].publicSummary")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("矿工拒绝"))));
        assertThat(jdbc.queryForObject("""
                select count(*) from v2_domain_change where save_id=? and change_type='EVENT_TIME_ADVANCED'
                """, Integer.class, saveId)).isEqualTo(1);
    }

    @Test
    void archivesOnlyAfterExactNameConfirmationAndFreesAnActiveSlot() throws Exception {
        JsonNode created = createSave("顾迟", "HUMAN", "行脚少年", "走遍青岐州的旧驿道");
        String saveId = created.path("save").path("id").asText();
        http.perform(post("/api/v2/saves/{saveId}/archive", saveId).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"characterName\":\"顾早\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ARCHIVE_CONFIRMATION_MISMATCH"));
        http.perform(post("/api/v2/saves/{saveId}/archive", saveId).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"characterName\":\"顾迟\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.save.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.save.endReason").value("PLAYER_ARCHIVED"))
                .andExpect(jsonPath("$.protagonist.status").value("ARCHIVED"));
        assertThat(jdbc.queryForObject(
                "select count(*) from v2_game_save where id=? and status='ACTIVE'", Integer.class, saveId))
                .isZero();
    }

    @Test
    void returnsTheAccountsCurrentOperationSoAMobileRefreshCanResumeIt() throws Exception {
        JsonNode created = createSave("照川", "SPIRIT", "河石初灵", "记住每次潮汐的声音");
        String saveId = created.path("save").path("id").asText();
        String userId = created.path("save").path("userId").asText();
        Instant now = Instant.now();
        String operationId = "recoverable-" + UUID.randomUUID();
        jdbc.update("""
                insert into v2_turn_operation(id,request_id,save_id,user_id,expected_state_version,
                  operation_status,operation_stage,action_type,action_text,action_payload,created_at)
                values (?,?,?,?,0,'INTERPRETING','INTERPRETING','FREE','观察水纹','{}',?)
                """, operationId, "request-" + UUID.randomUUID(), saveId, userId, Timestamp.from(now));
        http.perform(get("/api/v2/turn-operations/active").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.id").value(operationId))
                .andExpect(jsonPath("$.operation.saveId").value(saveId));
    }

    @Test
    void pagesOlderChronicleEntriesWithoutDroppingLongRunningSaves() throws Exception {
        JsonNode created = createSave("长卷", "HUMAN", "行脚人", "记下青岐州每一段旧路");
        String saveId = created.path("save").path("id").asText();
        String userId = created.path("save").path("userId").asText();
        Instant now = Instant.now();
        for (int turn = 1; turn <= 65; turn++) {
            String operationId = "history-operation-" + UUID.randomUUID();
            String turnId = "history-turn-" + UUID.randomUUID();
            jdbc.update("""
                    insert into v2_turn_operation(id,request_id,save_id,user_id,expected_state_version,
                      operation_status,operation_stage,action_type,action_text,action_payload,result_turn_id,
                      created_at,started_at,completed_at)
                    values (?,?,?,?,?,'SUCCEEDED','DONE','FREE',?,'{}',?,?,?,?)
                    """, operationId, "history-request-" + UUID.randomUUID(), saveId, userId, turn - 1,
                    "记录第" + turn + "段路", turnId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    insert into v2_turn(id,operation_id,save_id,turn_number,player_action,action_intent,
                      outcome_code,outcome_summary,narrative_text,choices_payload,world_minute_before,
                      world_minute_after,state_version_before,state_version_after,model_provider,model_code,created_at)
                    values (?,?,?,?,?,'{}','ACTION_PROGRESSED',?,?,'[]',?,?,?,?,null,null,?)
                    """, turnId, operationId, saveId, turn, "记录第" + turn + "段路",
                    "第" + turn + "段路已经记入命卷", "这是一段已经确认的旅途。", turn - 1L, turn,
                    turn - 1L, (long) turn, Timestamp.from(now));
        }

        http.perform(get("/api/v2/saves/{saveId}/chronicle", saveId).cookie(session)
                        .param("limit", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(60))
                .andExpect(jsonPath("$[0].turnNumber").value(65))
                .andExpect(jsonPath("$[59].turnNumber").value(6));
        http.perform(get("/api/v2/saves/{saveId}/chronicle", saveId).cookie(session)
                        .param("limit", "60").param("beforeTurn", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].turnNumber").value(5))
                .andExpect(jsonPath("$[4].turnNumber").value(1));
    }

    private String submitFree(String saveId, long stateVersion, String actionText) throws Exception {
        MvcResult submitted = http.perform(post("/api/v2/saves/{saveId}/actions", saveId)
                        .cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"effect-%s","expectedStateVersion":%d,"actionType":"FREE",
                                 "actionText":"%s"}
                                """.formatted(UUID.randomUUID(), stateVersion, actionText)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(submitted.getResponse().getContentAsByteArray()).path("id").asText();
    }

    private JsonNode createSave(String name, String race, String rootType, String lifeGoal) throws Exception {
        String gender = "SPIRIT".equals(race) ? "不设定" : "女";
        MvcResult response = http.perform(post("/api/v2/saves").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","genderExpression":"%s","race":"%s","rootType":"%s",
                                 "rootDescription":"青岐州边地一段已经确认的普通来历","lifeGoal":"%s",
                                 "difficulty":"NORMAL"}
                                """.formatted(name, gender, race, rootType, lifeGoal)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }

    private JsonNode awaitOperation(String operationId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        JsonNode operation;
        do {
            MvcResult response = http.perform(get("/api/v2/turn-operations/{operationId}", operationId)
                            .cookie(session))
                    .andExpect(status().isOk())
                    .andReturn();
            operation = json.readTree(response.getResponse().getContentAsByteArray());
            if (operation.path("status").asText().matches("SUCCEEDED|REJECTED|FAILED")) return operation;
            Thread.sleep(20);
        } while (Instant.now().isBefore(deadline));
        return operation;
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
