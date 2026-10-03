package com.wenxian.game.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSchemaContractValidatorTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("game-prod-schema-contract").generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table app_user(id varchar(64) primary key,email varchar,password_hash varchar,password_set_at timestamp,registration_ip_hash varchar,status varchar)");
        jdbc.execute("create table auth_email_verification(id varchar,user_id varchar,purpose varchar,email_digest varchar,request_ip_digest varchar,code_digest varchar,status varchar,attempt_count integer,max_attempts integer,expires_at timestamp,sent_at timestamp,consumed_at timestamp,failure_code varchar)");
        jdbc.execute("create table account_retention_state(user_id varchar,last_meaningful_activity_at timestamp,activity_source varchar,policy_state varchar,scheduled_deletion_at timestamp)");
        jdbc.execute("create table account_deletion_job(id varchar,user_id varchar,subject_digest varchar,reason varchar,status varchar,delete_counts_json clob)");
        jdbc.execute("create table account_deletion_audit(id varchar,subject_digest varchar,reason varchar,delete_counts_json clob,completed_at timestamp)");
        jdbc.execute("create table account_deletion_notice(id varchar,user_id varchar,activity_version bigint,scheduled_deletion_at timestamp,status varchar,next_attempt_at timestamp)");
        jdbc.execute("create table account_lifecycle_event(id varchar,event_key varchar,event_type varchar,reason varchar,reference_id varchar,error_code varchar,metadata_json clob,occurred_at timestamp)");
        jdbc.execute("create table account_retention_run(id varchar,trigger_source varchar,status varchar,policy_evaluation_enabled boolean,notification_delivery_enabled boolean,automatic_deletion_enabled boolean,mail_gateway_ready boolean,notice_after_days integer,delete_after_days integer,minimum_notice_days integer,batch_size integer,notices_prepared integer,notices_delivered integer,deletion_jobs_prepared integer,error_code varchar,started_at timestamp,finished_at timestamp)");
        jdbc.execute("create table ai_usage_log(user_id varchar,attribution_status varchar)");
        jdbc.execute("create table operation_error(subject_user_id varchar)");
        jdbc.execute("create table gameplay_config(config_key varchar,config_value varchar,updated_at timestamp)");
        jdbc.execute("create table story_save(id varchar,user_id varchar,active_slot_no integer,status varchar,state_schema_version integer,state_json clob,deleted_at timestamp,revision integer)");
        jdbc.execute("create table story_turn(id varchar,save_id varchar,request_id varchar,engine_code varchar,state_schema_version integer,credential_source varchar)");
        jdbc.execute("create table story_state_correction(id varchar,save_id varchar,user_id varchar,correction_type varchar,state_schema_version integer)");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("world-package-registry-h2.sql")).execute(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void acceptsTheMinimumProductionSchemaWithoutWritingData() {
        assertDoesNotThrow(() -> new ProductionSchemaContractValidator(jdbc).validate());
    }

    @Test
    void rejectsAProductionSchemaMissingARequiredContract() {
        jdbc.execute("drop table story_turn");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ProductionSchemaContractValidator(jdbc).validate());

        assertTrue(error.getMessage().contains("剧情回合"));
    }

    @Test
    void rejectsProductionSchemaWithoutEmailVerificationChallenges() {
        jdbc.execute("drop table auth_email_verification");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ProductionSchemaContractValidator(jdbc).validate());

        assertTrue(error.getMessage().contains("注册邮箱验证码"));
    }
}
