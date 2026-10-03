package com.wenxian.game.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 在生产 profile 启动时只读验证游戏后端所依赖的最低数据库结构。
 *
 * <p>本组件不创建、升级或回填任何表。任一关键契约缺失时直接阻止应用进入可服务状态，
 * 由发布流程先执行显式迁移并重新启动。</p>
 */
@Component
@Profile("prod")
public class ProductionSchemaContractValidator {
    private static final List<SchemaContract> CONTRACTS = List.of(
            new SchemaContract("账号认证",
                    "select id,email,password_hash,password_set_at,registration_ip_hash,status from app_user where 1=0"),
            new SchemaContract("注册邮箱验证码",
                    "select id,user_id,purpose,email_digest,request_ip_digest,code_digest,status,attempt_count,max_attempts,"
                            + "expires_at,sent_at,consumed_at,failure_code from auth_email_verification where 1=0"),
            new SchemaContract("账号留存状态",
                    "select user_id,last_meaningful_activity_at,activity_source,policy_state,scheduled_deletion_at "
                            + "from account_retention_state where 1=0"),
            new SchemaContract("账号删除任务",
                    "select id,user_id,subject_digest,reason,status,delete_counts_json "
                            + "from account_deletion_job where 1=0"),
            new SchemaContract("账号删除审计",
                    "select id,subject_digest,reason,delete_counts_json,completed_at "
                            + "from account_deletion_audit where 1=0"),
            new SchemaContract("账号删除通知",
                    "select id,user_id,activity_version,scheduled_deletion_at,status,next_attempt_at "
                            + "from account_deletion_notice where 1=0"),
            new SchemaContract("账号生命周期匿名事件",
                    "select id,event_key,event_type,reason,reference_id,error_code,metadata_json,occurred_at "
                            + "from account_lifecycle_event where 1=0"),
            new SchemaContract("账号留存调度运行",
                    "select id,trigger_source,status,policy_evaluation_enabled,notification_delivery_enabled,"
                            + "automatic_deletion_enabled,mail_gateway_ready,notice_after_days,delete_after_days,"
                            + "minimum_notice_days,batch_size,notices_prepared,notices_delivered,"
                            + "deletion_jobs_prepared,error_code,started_at,finished_at "
                            + "from account_retention_run where 1=0"),
            new SchemaContract("可归属调用与错误日志",
                    "select l.user_id,l.attribution_status,e.subject_user_id from ai_usage_log l "
                            + "left join operation_error e on 1=0 where 1=0"),
            new SchemaContract("运行配置",
                    "select config_key,config_value,updated_at from gameplay_config where 1=0"),
            new SchemaContract("命卷快照",
                    "select id,user_id,active_slot_no,status,state_schema_version,state_json,deleted_at,revision "
                            + "from story_save where 1=0"),
            new SchemaContract("剧情回合",
                    "select id,save_id,request_id,engine_code,state_schema_version,credential_source "
                            + "from story_turn where 1=0"),
            new SchemaContract("世界包注册表",
                    "select p.publisher_namespace,p.package_key,p.owner_user_id,p.latest_revision,r.content_hash,r.compiled_hash,r.runtime_contract,r.canonical_json,r.compiled_json,r.compiler_version "
                            + "from world_package p join world_package_revision r on r.package_id=p.id where 1=0"),
            new SchemaContract("世界包发布与素材",
                    "select a.asset_bytes,a.content_hash,q.request_hash,q.principal_key,q.idempotency_key from world_package_asset a "
                            + "left join world_package_publication_request q on q.revision_id=a.revision_id where 1=0"),
            new SchemaContract("世界包安装与显式创角范围",
                    "select i.user_id,s.scope_type,a.revision_id,d.revision_id,e.actor_key from world_package_installation i "
                            + "left join world_package_creation_scope s on s.user_id=i.user_id "
                            + "left join world_package_availability a on a.scope_id=s.id "
                            + "left join world_package_creation_default d on d.scope_id=s.id "
                            + "left join world_package_audit e on e.scope_id=s.id where 1=0"),
            new SchemaContract("命卷修正审计",
                    "select id,save_id,user_id,correction_type,state_schema_version "
                            + "from story_state_correction where 1=0")
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
