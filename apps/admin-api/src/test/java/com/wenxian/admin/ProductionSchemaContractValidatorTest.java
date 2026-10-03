package com.wenxian.admin;

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
                .setName("admin-prod-schema-contract").generateUniqueName(true).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table admin_user(id varchar,username varchar,password_hash varchar,status varchar)");
        jdbc.execute("create table admin_operation_log(subject_user_id varchar,operated_at timestamp)");
        jdbc.execute("create table ai_usage_log(user_id varchar,attribution_status varchar,created_at timestamp)");
        jdbc.execute("create table account_retention_state(user_id varchar,policy_state varchar,scheduled_deletion_at timestamp,updated_at timestamp)");
        jdbc.execute("create table account_deletion_notice(id varchar,user_id varchar,status varchar,scheduled_deletion_at timestamp,attempt_count integer,next_attempt_at timestamp,last_error_code varchar,updated_at timestamp)");
        jdbc.execute("create table account_deletion_job(id varchar,user_id varchar,status varchar,reason varchar,attempt_count integer,last_error_code varchar,requested_at timestamp,updated_at timestamp,completed_at timestamp)");
        jdbc.execute("create table account_deletion_audit(id varchar,reason varchar,delete_counts_json clob,completed_at timestamp)");
        jdbc.execute("create table account_lifecycle_event(id varchar,event_type varchar,reason varchar,reference_id varchar,error_code varchar,metadata_json clob,occurred_at timestamp)");
        jdbc.execute("create table account_retention_run(id varchar,trigger_source varchar,status varchar,policy_evaluation_enabled boolean,notification_delivery_enabled boolean,automatic_deletion_enabled boolean,mail_gateway_ready boolean,notice_after_days integer,delete_after_days integer,minimum_notice_days integer,batch_size integer,notices_prepared integer,notices_delivered integer,deletion_jobs_prepared integer,error_code varchar,started_at timestamp,finished_at timestamp)");
        jdbc.execute("create table app_user(id varchar,email varchar,status varchar,registered_at timestamp,last_login_at timestamp)");
        jdbc.execute("create table gameplay_config(config_key varchar,config_value varchar,updated_at timestamp)");
        jdbc.execute("create table story_save(id varchar,user_id varchar,active_slot_no integer,status varchar,state_schema_version integer,state_json clob,deleted_at timestamp,revision integer)");
        jdbc.execute("create table story_turn(id varchar,save_id varchar,request_id varchar,engine_code varchar,state_schema_version integer,credential_source varchar)");
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
        jdbc.execute("drop table admin_user");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ProductionSchemaContractValidator(jdbc).validate());

        assertTrue(error.getMessage().contains("管理员账号"));
    }
}
