package com.wenxian.admin;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 在生产 profile 启动时只读验证管理后端所依赖的最低共享数据库结构。
 *
 * <p>本组件不创建、升级或回填任何表。方案、点数等延期能力不在最低契约中，
 * 它们继续由各自功能开关和发布前迁移门禁控制。</p>
 */
@Component
@Profile("prod")
public class ProductionSchemaContractValidator {
    private static final List<SchemaContract> CONTRACTS = List.of(
            new SchemaContract("管理员账号",
                    "select id,username,password_hash,status from admin_user where 1=0"),
            new SchemaContract("玩家账号",
                    "select id,email,status,registered_at,last_login_at from app_user where 1=0"),
            new SchemaContract("运行配置",
                    "select config_key,config_value,updated_at from gameplay_config where 1=0"),
            new SchemaContract("可归属管理日志",
                    "select subject_user_id,operated_at from admin_operation_log where 1=0"),
            new SchemaContract("模型调用归属",
                    "select user_id,attribution_status,created_at from ai_usage_log where 1=0"),
            new SchemaContract("账号留存状态",
                    "select user_id,policy_state,scheduled_deletion_at,updated_at "
                            + "from account_retention_state where 1=0"),
            new SchemaContract("账号删除通知与任务",
                    "select n.id,n.user_id,n.status,n.scheduled_deletion_at,n.attempt_count,n.next_attempt_at,"
                            + "n.last_error_code,n.updated_at,j.id,j.user_id,j.status,j.reason,j.attempt_count,"
                            + "j.last_error_code,j.requested_at,j.updated_at,j.completed_at "
                            + "from account_deletion_notice n left join account_deletion_job j on 1=0 where 1=0"),
            new SchemaContract("账号删除匿名审计",
                    "select id,reason,delete_counts_json,completed_at from account_deletion_audit where 1=0"),
            new SchemaContract("账号生命周期匿名事件",
                    "select id,event_type,reason,reference_id,error_code,metadata_json,occurred_at "
                            + "from account_lifecycle_event where 1=0"),
            new SchemaContract("账号留存调度运行",
                    "select id,trigger_source,status,policy_evaluation_enabled,notification_delivery_enabled,"
                            + "automatic_deletion_enabled,mail_gateway_ready,notice_after_days,delete_after_days,"
                            + "minimum_notice_days,batch_size,notices_prepared,notices_delivered,"
                            + "deletion_jobs_prepared,error_code,started_at,finished_at "
                            + "from account_retention_run where 1=0"),
            new SchemaContract("命卷快照",
                    "select id,user_id,active_slot_no,status,state_schema_version,state_json,deleted_at,revision "
                            + "from story_save where 1=0"),
            new SchemaContract("剧情回合",
                    "select id,save_id,request_id,engine_code,state_schema_version,credential_source "
                            + "from story_turn where 1=0")
    );

    private final JdbcTemplate jdbc;

    public ProductionSchemaContractValidator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void validate() {
        for (SchemaContract contract : CONTRACTS) {
            try {
                jdbc.query(contract.probeSql(), result -> null);
            } catch (DataAccessException error) {
                throw new IllegalStateException("生产数据库结构校验失败：" + contract.name()
                        + "。请先完成显式迁移，应用不会自动修改表结构。", error);
            }
        }
    }

    private record SchemaContract(String name, String probeSql) { }
}
