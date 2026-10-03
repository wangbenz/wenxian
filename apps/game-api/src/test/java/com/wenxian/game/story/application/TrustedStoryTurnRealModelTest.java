package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.application.ModelRoutePolicy;
import com.wenxian.game.model.application.ModelTaskRouter;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.infrastructure.AiServiceRegistry;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import com.wenxian.game.model.infrastructure.PromptResourceLoader;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.ai.LangChain4jDirectedNarrativeGenerator;
import com.wenxian.game.story.agent.ai.StoryAiServiceRegistry;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import com.wenxian.game.story.reducer.StoryStateReducer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 通过真实模型验证普通回合从权威快照、类型化导演、Reducer 到 H2 原子提交的完整链路。
 * 测试只使用临时数据库与隔离账号，不连接 RDS，也不会读写真实玩家命卷。
 */
class TrustedStoryTurnRealModelTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void preparesAndCommitsOneCompleteTurnWithRealModel() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set DEEPSEEK_API_KEY to run full trusted turn smoke");
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryTurnRepository turns = new StoryTurnRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryConversationRepository conversations = new StoryConversationRepository(jdbc);
            StoryProjectionPersistenceMapper projections = new StoryProjectionPersistenceMapper(mapper);
            ChatModelRegistry models = new ChatModelRegistry(new OpenAiCompatibleChatModelFactory(), 4);
            ModelDefinition definition = definition();
            ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(
                    CredentialSource.PLATFORM, List.of(new ModelRouteCandidate(definition,
                    ModelCredential.platform(key), options(request.task()))));
            ModelTaskRouter router = new ModelTaskRouter(policy);
            ModelInvocationService invocations = new ModelInvocationService(router, models);
            LangChain4jDirectedNarrativeGenerator generator = new LangChain4jDirectedNarrativeGenerator(
                    router, invocations, new StoryAiServiceRegistry(
                    new AiServiceRegistry(new PromptResourceLoader(), 8)), mapper);
            LegacyDirectedTurnPreparationService preparation = new LegacyDirectedTurnPreparationService(
                    entities, memories, conversations, turns, projections, new PromptContextAssembler(),
                    new StoryStateReducer(new StoryPatchValidator(), prefix -> prefix + "-real-chain"),
                    com.wenxian.game.story.worldpackage.WorldPackageRegistryTestSupport.worldContexts(mapper),
                    null, generator);
            StoredSave saved = saves.findOwned("real-chain-user", "real-chain-save").orElseThrow();

            long started = System.nanoTime();
            var session = preparation.openSession(saved, "real-chain-request");
            PreparedStoryTurn prepared = preparation.prepare(saved, "real-chain-request",
                    "站在石门外仔细观察门上的旧纹，确认眼前情况后停下，不进入山门", null, session);
            StoryTurnCommitService commit = new StoryTurnCommitService(saves, turns, entities, memories,
                    conversations, projections, new GameVersionService(jdbc),
                    new PlatformTurnAccessService(null, null, false),
                    new TransactionTemplate(new DataSourceTransactionManager(database)), mapper);
            StoryTurnCommitService.Committed committed = commit.commit(saved.userId(), saved,
                    "real-chain-request", "站在石门外仔细观察门上的旧纹，确认眼前情况后停下，不进入山门",
                    prepared, null);

            StoredSave updated = saves.findOwned(saved.userId(), saved.id()).orElseThrow();
            var storedTurn = turns.findByRequestId(saved.id(), "real-chain-request").orElseThrow();
            assertThat(committed.turnNumber()).isEqualTo(1);
            assertThat(updated.turnNumber()).isEqualTo(1);
            assertThat(updated.revision()).isEqualTo(2);
            assertThat(storedTurn.narrative()).contains("你").doesNotContain("```", "{\"title\"");
            assertThat(storedTurn.promptVersion()).isEqualTo("typed-directed-turn-v8");
            assertThat(storedTurn.promptTokens()).isPositive();
            assertThat(storedTurn.completionTokens()).isPositive();
            assertThat(storedTurn.statePatch().path("trustedEvents").isArray()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from story_turn", Integer.class)).isEqualTo(1);
            System.out.printf("TRUSTED_TURN_REAL_CHAIN latencyMs=%d calls=%d tokens=%d/%d chars=%d model=%s%n",
                    (System.nanoTime() - started) / 1_000_000,
                    prepared.generation().modelCalls().size(), prepared.generation().inputTokens(),
                    prepared.generation().outputTokens(), storedTurn.narrative().length(), storedTurn.model());
        } finally {
            database.shutdown();
        }
    }

    private ModelDefinition definition() {
        return new ModelDefinition("DEEPSEEK", "TEST_MODEL",
                value(System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"),
                ModelAdapterType.OPENAI_COMPATIBLE,
                value(System.getenv("DEEPSEEK_MODEL"), "deepseek-v4-flash"),
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL,
                        ModelCapability.CACHE_USAGE), 128_000, 8_000);
    }

    private ModelInvocationOptions options(com.wenxian.game.model.domain.ModelTask task) {
        int outputTokens = task == com.wenxian.game.model.domain.ModelTask.TURN_DIRECTOR ? 2200 : 3200;
        double temperature = task == com.wenxian.game.model.domain.ModelTask.TURN_DIRECTOR ? 0.3 : 0.8;
        return new ModelInvocationOptions(temperature, Duration.ofSeconds(90), 0, outputTokens, true);
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                "real-chain-user", "real-chain@test.local", "ACTIVE", now, now);
        var state = com.wenxian.game.story.support.StoryTestFixtures.state(
                mapper, "real-chain-save", 0, "青石山门前",
                "你刚抵达青石山门，尚未决定是否进入。");
        new StorySaveRepository(jdbc, mapper).insert(new NewSave("real-chain-save", "real-chain-user",
                1, 1, "ACTIVE", "XUANXU", "LIGHT_NARRATIVE", 1, 1, "NATIVE",
                "V0.16", null, null, state, 0, "LOCAL_TEST", "LOCAL_TEST", now.toInstant()));
        new StoryConversationRepository(jdbc).initialize("real-chain-save",
                "你刚抵达青石山门，尚未决定是否进入。", 0);
        return database;
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}
