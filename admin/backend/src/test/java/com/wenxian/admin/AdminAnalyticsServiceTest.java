package com.wenxian.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminAnalyticsServiceTest {

    @Test
    void growthBuildsActivationFunnelFromRegisteredCohort() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("create table app_user(id varchar(64) primary key, status varchar(20), registered_at timestamp)");
            jdbc.execute("""
                    create table analytics_user_day(
                        activity_date date, user_id varchar(64), created_save_count integer,
                        successful_turn_count integer)
                    """);
            jdbc.execute("""
                    create table analytics_daily_metric(
                        metric_date date, metric_code varchar(64), dimension_type varchar(32),
                        dimension_value varchar(120), metric_value decimal(24,4), calculated_at timestamp,
                        primary key(metric_date,metric_code,dimension_type,dimension_value))
                    """);
            jdbc.execute("""
                    create table analytics_retention_cohort(
                        cohort_date date, retention_day integer, registered_users integer,
                        activated_users integer, retained_users integer, data_origin varchar(20))
                    """);
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("insert into app_user values ('u1','ACTIVE',?)", now);
            jdbc.update("insert into app_user values ('u2','ACTIVE',?)", now);
            jdbc.update("insert into analytics_user_day values (current_date,'u1',1,1)");

            @SuppressWarnings("unchecked")
            var funnel = (java.util.Map<String, Object>) new AdminAnalyticsService(jdbc).growth(30).get("funnel");

            assertEquals(2L, funnel.get("registered"));
            assertEquals(1L, funnel.get("createdSave"));
            assertEquals(1L, funnel.get("firstTurn"));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void overviewReadsOnlyIndependentDailyMetrics() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("create table app_user(id varchar(64) primary key, status varchar(20))");
            jdbc.execute("""
                    create table analytics_daily_metric(
                        metric_date date, metric_code varchar(64), dimension_type varchar(32),
                        dimension_value varchar(120), metric_value decimal(24,4), calculated_at timestamp,
                        primary key(metric_date,metric_code,dimension_type,dimension_value))
                    """);
            LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("insert into app_user values ('u1','ACTIVE')");
            insertMetric(jdbc, today, "REGISTERED_USERS", 1, now);
            insertMetric(jdbc, today, "ACTIVE_USERS", 1, now);
            insertMetric(jdbc, today, "SUCCESSFUL_TURNS", 3, now);
            insertMetric(jdbc, today, "FINAL_ERRORS", 0, now);

            var overview = new AdminAnalyticsService(jdbc).overview(14);

            assertEquals(1L, overview.get("totalUsers"));
            assertEquals(3L, overview.get("totalSuccessfulTurns"));
            assertEquals(14, ((java.util.List<?>) overview.get("trend")).size());
        } finally {
            database.shutdown();
        }
    }

    private void insertMetric(JdbcTemplate jdbc, LocalDate date, String code, long value, Timestamp now) {
        jdbc.update("insert into analytics_daily_metric values (?,?,'ALL','ALL',?,?)",
                Date.valueOf(date), code, BigDecimal.valueOf(value), now);
    }
}
