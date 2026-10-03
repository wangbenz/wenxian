package com.wenxian.game.story.infrastructure;

import com.wenxian.game.account.AccountActivityService;
import com.wenxian.game.credit.PlatformTurnQuotaService;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.application.ServicePlanRuntimeService;
import com.wenxian.game.model.persistence.JdbcModelCatalogRepository;
import com.wenxian.game.model.persistence.JdbcServicePlanRuntimeRepository;
import com.wenxian.game.model.persistence.ModelCatalogInitializer;
import com.wenxian.game.story.model.GenerationApiModels.CredentialRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 统一设置链必须只写中性偏好表，并让回合选择立即读取同一事实。 */
class JdbcGenerationSettingsGatewayTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private JdbcGenerationSettingsGateway gateway;
    private AtomicReference<String> verifiedKey;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        jdbc = new JdbcTemplate(database);
        Instant now = Instant.now();
        jdbc.update("""
                insert into app_user(id,email,status,registered_at,last_login_at)
                values ('generation-user','generation@test.invalid','ACTIVE',?,?)
                """, Timestamp.from(now), Timestamp.from(now));

        new ModelCatalogInitializer(jdbc, "https://api.deepseek.com",
                "sk-platform-test-secret-123456789", "deepseek-v4-flash", "master-key", false).initialize();
        var catalog = new JdbcModelCatalogRepository(jdbc);
        jdbc.update("""
                insert into ai_service_plan_version(id,version_number,status,revision,default_tier_code,
                    change_note,created_by,created_at,published_by,published_at)
                values ('settings-plan',1,'ACTIVE',1,'BALANCED','设置测试','admin',?,'admin',?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into ai_service_plan_tier(plan_version_id,tier_code,display_name,description,point_cost,
                    enabled,fallback_enabled)
                values ('settings-plan','LIGHT','轻量','快速推进',1,true,false),
                       ('settings-plan','BALANCED','均衡','默认体验',2,true,false),
                       ('settings-plan','DEEP','深度','细致推演',4,true,false)
                """);
        PlatformTurnQuotaService quota = new PlatformTurnQuotaService(jdbc);
        quota.ensureDefaults();
        verifiedKey = new AtomicReference<>();
        ChatModelRegistry registry = new ChatModelRegistry((definition, credential, options) -> {
            throw new AssertionError("设置持久化测试不应创建模型客户端");
        }, 4);
        var servicePlans = new ServicePlanRuntimeService(catalog,
                new JdbcServicePlanRuntimeRepository(jdbc), true);
        gateway = new JdbcGenerationSettingsGateway(jdbc, quota, catalog,
                (model, apiKey) -> verifiedKey.set(model.identity() + ":" + apiKey),
                registry, servicePlans, "master-key", new AccountActivityService(jdbc));
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void savesPersonalCredentialAndSelectionOnlyToNeutralTables() {
        var saved = gateway.saveCredential("generation-user", "DEEPSEEK",
                new CredentialRequest("sk-personal-test-secret-123456789", "deepseek-v4-pro"));

        assertThat(verifiedKey.get()).isEqualTo(
                "DEEPSEEK:deepseek-v4-pro:sk-personal-test-secret-123456789");
        assertThat(saved.mode()).isEqualTo("PERSONAL");
        assertThat(saved.selectedPersonalModel()).isEqualTo("deepseek-v4-pro");
        assertThat(gateway.currentSelection("generation-user").personal()).isTrue();
        assertThat(jdbc.queryForObject("""
                select activity_source from account_retention_state where user_id='generation-user'
                """, String.class)).isEqualTo("BYOK_CHANGED");
        assertThat(jdbc.queryForObject("""
                select credential_source from user_model_preference where user_id='generation-user'
                """, String.class)).isEqualTo("USER");
        var platform = gateway.saveSettings("generation-user",
                new SettingsRequest("PLATFORM", null, null, "DEEP"));
        assertThat(platform.mode()).isEqualTo("PLATFORM");
        assertThat(platform.selectedPlatformTier()).isEqualTo("DEEP");
        assertThat(platform.platformTiers()).extracting(tier -> tier.code())
                .containsExactly("LIGHT", "BALANCED", "DEEP");
        assertThat(gateway.currentSelection("generation-user").personal()).isFalse();
    }

    @Test
    void invalidatesAndDeletesCredentialWithoutLeakingItIntoSelection() {
        gateway.saveCredential("generation-user", "DEEPSEEK",
                new CredentialRequest("sk-personal-test-secret-123456789", "deepseek-v4-flash"));

        gateway.markCredentialInvalid("generation-user", "deepseek", "AUTHENTICATION");
        assertThat(jdbc.queryForObject("""
                select status from user_ai_provider_credential
                where user_id='generation-user' and provider_code='DEEPSEEK'
                """, String.class)).isEqualTo("INVALID");

        var settings = gateway.deleteCredential("generation-user", "DEEPSEEK");
        assertThat(settings.mode()).isEqualTo("PLATFORM");
        assertThat(jdbc.queryForObject("""
                select activity_source from account_retention_state where user_id='generation-user'
                """, String.class)).isEqualTo("BYOK_DELETED");
        assertThat(settings.personalProviders().getFirst().credentialConfigured()).isFalse();
        assertThat(jdbc.queryForObject("""
                select count(*) from user_ai_provider_credential where user_id='generation-user'
                """, Integer.class)).isZero();
    }

    @Test
    void startupNeverOverwritesExistingNeutralPreference() {
        Instant now = Instant.now();
        jdbc.update("""
                insert into user_model_preference(user_id,provider_code,model_code,credential_source,updated_at)
                values ('generation-user','DEEPSEEK','deepseek-v4-flash','PUBLIC',?)
                """, Timestamp.from(now));
        new ModelCatalogInitializer(jdbc, "https://api.deepseek.com",
                "sk-platform-test-secret-123456789", "deepseek-v4-pro", "master-key", false).initialize();

        assertThat(jdbc.queryForMap("""
                select model_code,credential_source from user_model_preference where user_id='generation-user'
                """)).containsEntry("MODEL_CODE", "deepseek-v4-flash").containsEntry("CREDENTIAL_SOURCE", "PUBLIC");
    }

    @Test
    void singleStoryRouteRemainsAvailableWithoutOldWriterAndSuppliesTheDefaultPreference() {
        jdbc.update("update ai_service_plan_version set status='DRAFT' where id='settings-plan'");
        jdbc.update("delete from ai_model_task_route where task_code='NARRATIVE_WRITER'");
        jdbc.update("update ai_model_task_route set primary_model_code='deepseek-v4-pro' where task_code='TURN_DIRECTOR'");

        var settings = gateway.settings("generation-user");
        assertThat(settings.platformTiers()).isEmpty();
        assertThat(settings.platform().available()).isTrue();
        assertThat(gateway.currentSelection("generation-user").model()).isEqualTo("deepseek-v4-pro");
        gateway.saveSettings("generation-user", new SettingsRequest("PLATFORM", null, null));
        assertThat(jdbc.queryForObject("select model_code from user_model_preference where user_id='generation-user'",
                String.class)).isEqualTo("deepseek-v4-pro");
        assertThat(verifiedKey.get()).isNull();
    }

    @Test
    void oldWriterAloneCannotMakeTheNewStoryRouteAvailable() {
        jdbc.update("update ai_service_plan_version set status='DRAFT' where id='settings-plan'");
        jdbc.update("delete from ai_model_task_route where task_code='TURN_DIRECTOR'");

        assertThat(gateway.settings("generation-user").platform().available()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from ai_model_task_route where task_code='NARRATIVE_WRITER'",
                Integer.class)).isEqualTo(1);
        assertThat(verifiedKey.get()).isNull();
    }
}
