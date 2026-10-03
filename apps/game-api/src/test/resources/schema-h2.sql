create table app_user (
    id varchar(64) primary key,
    email varchar(255) not null unique,
    password_hash varchar(100),
    password_set_at timestamp,
    registration_ip_hash varchar(128),
    status varchar(32) not null,
    registered_at timestamp not null,
    last_login_at timestamp
);

create table auth_registration (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    ip_hash varchar(128) not null,
    created_at timestamp not null,
    constraint fk_auth_registration_user foreign key (user_id) references app_user(id) on delete cascade
);

create table auth_login_failure (
    id varchar(64) primary key,
    email_hash varchar(128) not null,
    ip_hash varchar(128) not null,
    created_at timestamp not null
);

create table auth_email_verification (
    id varchar(64) primary key,
    user_id varchar(64),
    purpose varchar(32) not null,
    email_digest varchar(128) not null,
    request_ip_digest varchar(128) not null,
    code_digest varchar(128) not null,
    status varchar(32) not null,
    attempt_count integer not null default 0,
    max_attempts integer not null,
    expires_at timestamp not null,
    sent_at timestamp,
    consumed_at timestamp,
    failure_code varchar(64),
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint fk_auth_email_verification_user foreign key (user_id) references app_user(id) on delete cascade
);
create index idx_auth_email_verification_user on auth_email_verification(user_id);
create index idx_auth_email_verification_email_time on auth_email_verification(email_digest, created_at);
create index idx_auth_email_verification_ip_time on auth_email_verification(request_ip_digest, created_at);
create index idx_auth_email_verification_status_expiry on auth_email_verification(status, expires_at);

create table invite_code (
    id varchar(64) primary key,
    code_hash varchar(128) not null unique,
    status varchar(32) not null,
    max_uses integer not null,
    used_count integer not null default 0,
    expires_at timestamp,
    created_at timestamp not null
);

create table user_session (
    id varchar(64) primary key,
    user_id varchar(64) not null,
    token_hash varchar(128) not null unique,
    expires_at timestamp not null,
    revoked_at timestamp,
    created_at timestamp not null,
    constraint fk_session_user foreign key (user_id) references app_user(id)
);

create table account_retention_state (
    user_id varchar(64) primary key,
    last_meaningful_activity_at timestamp not null,
    activity_source varchar(40) not null,
    policy_state varchar(24) not null default 'ACTIVE',
    notice_sent_at timestamp,
    scheduled_deletion_at timestamp,
    canceled_at timestamp,
    activity_origin varchar(16) not null default 'REALTIME',
    version bigint not null default 0,
    updated_at timestamp not null,
    constraint fk_account_retention_user foreign key (user_id) references app_user(id) on delete cascade
);

create table account_deletion_job (
    id varchar(80) primary key,
    user_id varchar(64),
    subject_digest varchar(128) not null,
    reason varchar(32) not null,
    status varchar(24) not null,
    expected_activity_before timestamp,
    lease_owner varchar(80),
    lease_expires_at timestamp,
    attempt_count integer not null default 0,
    last_error_code varchar(80),
    delete_counts_json clob not null,
    requested_at timestamp not null,
    updated_at timestamp not null,
    completed_at timestamp
);

create table account_deletion_audit (
    id varchar(80) primary key,
    subject_digest varchar(128) not null,
    reason varchar(32) not null,
    delete_counts_json clob not null,
    completed_at timestamp not null
);

create table account_deletion_notice (
    id varchar(80) primary key,
    user_id varchar(64),
    activity_version bigint not null,
    scheduled_deletion_at timestamp not null,
    status varchar(24) not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamp,
    lease_owner varchar(80),
    lease_expires_at timestamp,
    last_error_code varchar(80),
    created_at timestamp not null,
    updated_at timestamp not null,
    sent_at timestamp,
    canceled_at timestamp,
    unique(user_id, activity_version),
    constraint fk_account_deletion_notice_user foreign key (user_id) references app_user(id) on delete cascade
);

create table account_lifecycle_event (
    id varchar(80) primary key,
    event_key varchar(200) not null unique,
    event_type varchar(48) not null,
    reason varchar(32),
    reference_id varchar(80) not null,
    error_code varchar(80),
    metadata_json clob not null,
    occurred_at timestamp not null
);

create table account_retention_run (
    id varchar(80) primary key,
    trigger_source varchar(24) not null,
    status varchar(24) not null,
    policy_evaluation_enabled boolean not null,
    notification_delivery_enabled boolean not null,
    automatic_deletion_enabled boolean not null,
    mail_gateway_ready boolean not null,
    notice_after_days integer not null,
    delete_after_days integer not null,
    minimum_notice_days integer not null,
    batch_size integer not null,
    notices_prepared integer not null default 0,
    notices_delivered integer not null default 0,
    deletion_jobs_prepared integer not null default 0,
    error_code varchar(80),
    started_at timestamp not null,
    finished_at timestamp
);

create table ai_usage_log (
    id varchar(80) primary key,
    request_id varchar(100),
    user_id varchar(64),
    attribution_status varchar(24) not null default 'UNRESOLVED',
    attempt_number integer not null default 1,
    fallback_used boolean not null default false,
    save_id varchar(80),
    turn_number integer,
    operation varchar(40) not null,
    provider varchar(32) not null default 'DEEPSEEK',
    credential_source varchar(20) not null,
    model varchar(120) not null,
    game_version varchar(32) not null default 'UNKNOWN',
    status varchar(20) not null,
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    cache_hit_tokens integer not null default 0,
    cache_miss_tokens integer not null default 0,
    latency_ms bigint not null default 0,
    error_detail varchar(1000) not null default '',
    created_at timestamp not null
);

create table agent_execution (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    turn_number integer not null,
    model varchar(80) not null,
    attempt_number integer not null,
    status varchar(24) not null,
    detail clob not null,
    response_id varchar(128),
    latency_ms bigint not null default 0,
    created_at timestamp(6) not null
);

create table operation_error (
    reference_id varchar(40) primary key,
    save_id varchar(80),
    subject_user_id varchar(64),
    operation varchar(120) not null,
    category varchar(32) not null,
    error_code varchar(64) not null,
    http_status integer not null,
    public_message varchar(600) not null,
    diagnostic clob not null,
    created_at timestamp not null
);

create table release_note (
    id varchar(80) primary key,
    version varchar(32) not null,
    title varchar(120) not null,
    content clob not null,
    status varchar(24) not null default 'DRAFT',
    announce_all boolean not null default false,
    show_on_login boolean not null default false,
    published_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null
);

create table user_release_receipt (
    user_id varchar(64) not null,
    release_note_id varchar(80) not null,
    read_at timestamp not null,
    primary key (user_id, release_note_id),
    constraint fk_release_receipt_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_release_receipt_note foreign key (release_note_id) references release_note(id) on delete cascade
);

create table ai_model_config (
    model_code varchar(40) primary key,
    provider_code varchar(32) not null default 'DEEPSEEK',
    display_name varchar(80) not null,
    secret_ciphertext clob,
    active boolean not null default false,
    updated_at timestamp not null
);

create table ai_provider_config (
    provider_code varchar(32) primary key,
    display_name varchar(80) not null,
    base_url varchar(255) not null,
    adapter_type varchar(32) not null default 'OPENAI_COMPATIBLE',
    platform_secret_ciphertext clob,
    enabled boolean not null default true,
    user_key_enabled boolean not null default true,
    config_version bigint not null default 1,
    updated_at timestamp not null
);

create table ai_provider_model (
    provider_code varchar(32) not null,
    model_code varchar(80) not null,
    display_name varchar(80) not null,
    api_model_name varchar(120) not null,
    capability_codes varchar(255) not null default 'CHAT,JSON_MODE',
    context_window integer not null default 64000,
    maximum_output_tokens integer not null default 8000,
    concurrency_limit integer not null default 4,
    platform_enabled boolean not null default true,
    user_selectable boolean not null default true,
    config_version bigint not null default 1,
    updated_at timestamp not null,
    primary key (provider_code, model_code),
    constraint fk_ai_provider_model_provider foreign key (provider_code) references ai_provider_config(provider_code) on delete cascade
);

create table ai_model_validation_run (
    id varchar(64) primary key,
    provider_code varchar(32) not null,
    model_code varchar(80) not null,
    provider_config_version bigint not null,
    model_config_version bigint not null,
    status varchar(24) not null,
    capability_codes varchar(255) not null default '',
    latency_ms integer,
    error_code varchar(80),
    error_summary varchar(500),
    checked_by varchar(64) not null,
    checked_at timestamp not null,
    constraint fk_model_validation_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code) on delete cascade
);

create table ai_model_task_route (
    task_code varchar(48) primary key,
    primary_provider_code varchar(32) not null,
    primary_model_code varchar(80) not null,
    fallback_provider_code varchar(32),
    fallback_model_code varchar(80),
    temperature decimal(4,3) not null default 0.700,
    timeout_seconds integer not null default 90,
    maximum_retries integer not null default 0,
    maximum_output_tokens integer,
    disable_thinking boolean not null default true,
    fallback_enabled boolean not null default false,
    updated_at timestamp not null,
    constraint fk_task_route_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_task_route_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code)
);

create table ai_service_plan_counter (
    counter_key varchar(32) primary key,
    next_value bigint not null
);

insert into ai_service_plan_counter(counter_key, next_value) values ('SERVICE_PLAN', 1);

create table ai_service_plan_version (
    id varchar(64) primary key,
    version_number bigint not null unique,
    status varchar(16) not null,
    revision bigint not null default 0,
    default_tier_code varchar(16) not null default 'BALANCED',
    change_note varchar(500) not null default '',
    based_on_version_id varchar(64),
    created_by varchar(64) not null,
    created_at timestamp not null,
    published_by varchar(64),
    published_at timestamp,
    retired_at timestamp,
    constraint fk_service_plan_based_on foreign key (based_on_version_id)
        references ai_service_plan_version(id)
);

create table ai_service_plan_tier (
    plan_version_id varchar(64) not null,
    tier_code varchar(16) not null,
    display_name varchar(40) not null,
    description varchar(240) not null default '',
    point_cost integer not null,
    enabled boolean not null default true,
    primary_provider_code varchar(32),
    primary_model_code varchar(80),
    fallback_provider_code varchar(32),
    fallback_model_code varchar(80),
    fallback_enabled boolean not null default false,
    primary key (plan_version_id, tier_code),
    constraint fk_service_plan_tier_version foreign key (plan_version_id)
        references ai_service_plan_version(id) on delete cascade,
    constraint fk_service_plan_tier_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_service_plan_tier_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code)
);

create table ai_service_plan_task_route (
    plan_version_id varchar(64) not null,
    tier_code varchar(16) not null,
    task_code varchar(48) not null,
    primary_provider_code varchar(32) not null,
    primary_model_code varchar(80) not null,
    fallback_provider_code varchar(32),
    fallback_model_code varchar(80),
    temperature decimal(4,3) not null default 0.700,
    timeout_seconds integer not null default 90,
    maximum_retries integer not null default 0,
    maximum_output_tokens integer,
    disable_thinking boolean not null default true,
    fallback_enabled boolean not null default false,
    primary key (plan_version_id, tier_code, task_code),
    constraint fk_service_plan_task_tier foreign key (plan_version_id, tier_code)
        references ai_service_plan_tier(plan_version_id, tier_code) on delete cascade,
    constraint fk_service_plan_task_primary_model foreign key (primary_provider_code, primary_model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_service_plan_task_fallback_model foreign key (fallback_provider_code, fallback_model_code)
        references ai_provider_model(provider_code, model_code)
);

create table ai_service_plan_model_snapshot (
    plan_version_id varchar(64) not null,
    provider_code varchar(32) not null,
    model_code varchar(80) not null,
    provider_config_version bigint not null,
    model_config_version bigint not null,
    validation_run_id varchar(64) not null,
    captured_at timestamp not null,
    primary key (plan_version_id, provider_code, model_code),
    constraint fk_service_plan_snapshot_version foreign key (plan_version_id)
        references ai_service_plan_version(id) on delete cascade,
    constraint fk_service_plan_snapshot_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code),
    constraint fk_service_plan_snapshot_validation foreign key (validation_run_id)
        references ai_model_validation_run(id)
);

create table user_service_tier_preference (
    user_id varchar(64) primary key,
    tier_code varchar(16) not null,
    updated_at timestamp not null,
    constraint fk_user_service_tier_preference_user foreign key (user_id)
        references app_user(id) on delete cascade,
    constraint ck_user_service_tier_preference_code check (tier_code in ('LIGHT','BALANCED','DEEP'))
);

create table gameplay_config (
    config_key varchar(64) primary key,
    config_value varchar(255) not null,
    updated_at timestamp not null
);

create table game_access_whitelist (
    id varchar(64) primary key,
    user_id varchar(64) not null unique,
    note varchar(200) not null default '',
    created_by varchar(64) not null default 'system',
    created_at timestamp not null,
    updated_at timestamp not null
);

create table user_ai_provider_credential (
    user_id varchar(64) not null,
    provider_code varchar(32) not null,
    api_key_ciphertext clob not null,
    masked_suffix varchar(8) not null,
    status varchar(24) not null default 'ACTIVE',
    last_verified_at timestamp,
    last_error_code varchar(48),
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (user_id, provider_code),
    constraint fk_user_provider_credential_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_user_provider_credential_provider foreign key (provider_code) references ai_provider_config(provider_code) on delete cascade
);

create table user_daily_quota (
    user_id varchar(64) not null,
    usage_date date not null,
    used_count integer not null default 0,
    updated_at timestamp not null,
    primary key (user_id, usage_date),
    constraint fk_daily_quota_user foreign key (user_id) references app_user(id) on delete cascade
);

create table turn_credit_grant (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    credit_class varchar(24) not null,
    grant_type varchar(32) not null,
    campaign_code varchar(64),
    source_reference varchar(160),
    idempotency_key varchar(180) not null unique,
    granted_count integer not null,
    available_count integer not null,
    reserved_count integer not null default 0,
    consumed_count integer not null default 0,
    expired_count integer not null default 0,
    revoked_count integer not null default 0,
    status varchar(24) not null,
    expires_at timestamp,
    reason varchar(240) not null,
    created_by varchar(64) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint fk_turn_credit_grant_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint ck_turn_credit_grant_nonnegative check (
        granted_count >= 0 and available_count >= 0 and reserved_count >= 0 and consumed_count >= 0
        and expired_count >= 0 and revoked_count >= 0
    ),
    constraint ck_turn_credit_grant_conservation check (
        granted_count = available_count + reserved_count + consumed_count + expired_count + revoked_count
    )
);

create index idx_turn_credit_grant_user_available
    on turn_credit_grant(user_id, status, expires_at, available_count);
create index idx_turn_credit_grant_campaign on turn_credit_grant(campaign_code, created_at);

create table turn_credit_reservation (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    save_id varchar(80) not null,
    request_id varchar(80) not null,
    grant_id varchar(80) not null,
    amount integer not null default 1,
    service_plan_version_id varchar(64),
    tier_code varchar(16),
    status varchar(24) not null,
    expires_at timestamp not null,
    turn_id varchar(80),
    created_at timestamp not null,
    updated_at timestamp not null,
    unique(user_id, save_id, request_id),
    constraint fk_turn_credit_reservation_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_turn_credit_reservation_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint fk_turn_credit_reservation_tier foreign key (service_plan_version_id, tier_code)
        references ai_service_plan_tier(plan_version_id, tier_code),
    constraint ck_turn_credit_reservation_amount check (amount > 0)
);

create index idx_turn_credit_reservation_expiry on turn_credit_reservation(status, expires_at);
create index idx_turn_credit_reservation_grant on turn_credit_reservation(grant_id, status);

create table turn_credit_reservation_allocation (
    reservation_id varchar(80) not null,
    grant_id varchar(80) not null,
    amount integer not null,
    primary key (reservation_id, grant_id),
    constraint fk_turn_credit_allocation_reservation foreign key (reservation_id)
        references turn_credit_reservation(id) on delete cascade,
    constraint fk_turn_credit_allocation_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint ck_turn_credit_allocation_amount check (amount > 0)
);

create index idx_turn_credit_allocation_grant
    on turn_credit_reservation_allocation(grant_id, reservation_id);

create table turn_credit_ledger (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    grant_id varchar(80) not null,
    reservation_id varchar(80),
    event_type varchar(24) not null,
    event_key varchar(200) not null unique,
    available_delta integer not null default 0,
    reserved_delta integer not null default 0,
    consumed_delta integer not null default 0,
    balance_after integer not null,
    business_reference varchar(160),
    operator_id varchar(64) not null,
    remark varchar(240) not null,
    created_at timestamp not null,
    constraint fk_turn_credit_ledger_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_turn_credit_ledger_grant foreign key (grant_id) references turn_credit_grant(id),
    constraint fk_turn_credit_ledger_reservation foreign key (reservation_id) references turn_credit_reservation(id)
);

create index idx_turn_credit_ledger_user_time on turn_credit_ledger(user_id, created_at);
create index idx_turn_credit_ledger_grant_time on turn_credit_ledger(grant_id, created_at);

create table story_engine_catalog (
    engine_code varchar(40) not null,
    engine_version integer not null,
    display_name varchar(80) not null,
    status varchar(24) not null,
    min_schema_version integer not null,
    current_schema_version integer not null,
    new_save_enabled boolean not null default false,
    continuation_enabled boolean not null default false,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (engine_code, engine_version)
);

insert into story_engine_catalog(engine_code, engine_version, display_name, status,
    min_schema_version, current_schema_version, new_save_enabled, continuation_enabled, created_at, updated_at)
values ('LIGHT_NARRATIVE', 1, '轻叙事引擎', 'DRAFT', 2, 2, false, false, current_timestamp, current_timestamp);

create table story_save (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    slot_no smallint not null,
    active_slot_no smallint,
    status varchar(24) not null,
    world_code varchar(40) not null,
    engine_code varchar(40) not null,
    engine_version integer not null,
    state_schema_version integer not null,
    source_type varchar(24) not null,
    source_edition varchar(32),
    source_save_id varchar(80),
    parent_save_id varchar(80),
    state_json clob not null,
    revision bigint not null default 1,
    turn_number integer not null default 0,
    created_release varchar(32) not null,
    last_played_release varchar(32) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    deleted_at timestamp,
    deleted_by varchar(24),
    delete_reason varchar(48),
    unique(user_id, slot_no),
    unique(user_id, active_slot_no),
    unique(source_type, source_save_id),
    constraint fk_story_save_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_story_save_parent foreign key (parent_save_id) references story_save(id),
    constraint fk_story_save_engine foreign key (engine_code, engine_version)
        references story_engine_catalog(engine_code, engine_version)
);

create table story_turn (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    request_id varchar(80) not null,
    turn_number integer not null,
    player_action clob not null,
    narrative_text clob not null,
    choices_json clob not null,
    state_patch_json clob not null,
    world_time_before bigint not null,
    world_time_after bigint not null,
    engine_code varchar(40) not null,
    engine_version integer not null,
    state_schema_version integer not null,
    prompt_version varchar(64) not null,
    provider varchar(32) not null,
    model varchar(120) not null,
    credential_source varchar(20) not null,
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    cache_hit_tokens integer not null default 0,
    cache_miss_tokens integer not null default 0,
    latency_ms bigint not null default 0,
    release_version varchar(32) not null,
    created_at timestamp not null,
    unique(save_id, turn_number),
    unique(save_id, request_id),
    constraint fk_story_turn_save foreign key (save_id) references story_save(id) on delete cascade
);

create table story_turn_diagnostic (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    request_id varchar(80) not null,
    turn_number integer not null,
    expected_revision bigint not null,
    prompt_version varchar(64) not null,
    status varchar(24) not null,
    current_stage varchar(48) not null,
    context_manifest_json clob not null,
    action_contract_json clob not null,
    proposal_trace_json clob not null,
    review_trace_json clob not null,
    commit_summary_json clob not null,
    failure_code varchar(64) not null default '',
    failure_detail varchar(1000) not null default '',
    created_at timestamp not null,
    updated_at timestamp not null,
    unique(save_id, request_id),
    constraint fk_story_turn_diagnostic_save foreign key (save_id) references story_save(id) on delete cascade
);

create table story_entity (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    entity_type varchar(24) not null,
    name varchar(120) not null,
    status varchar(80) not null,
    visible boolean not null default true,
    summary clob not null,
    attributes_json clob not null,
    first_turn integer not null,
    last_changed_turn integer not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    constraint fk_story_entity_save foreign key (save_id) references story_save(id) on delete cascade
);

create table story_entity_alias (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    entity_id varchar(80) not null,
    alias_name varchar(160) not null,
    normalized_alias varchar(160) not null,
    alias_type varchar(32) not null,
    source_turn integer,
    active boolean not null default true,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique(save_id, entity_id, normalized_alias),
    constraint fk_story_entity_alias_save foreign key (save_id) references story_save(id) on delete cascade,
    constraint fk_story_entity_alias_entity foreign key (entity_id) references story_entity(id) on delete cascade
);

create index idx_story_entity_alias_lookup on story_entity_alias(save_id, normalized_alias, active);

create table story_memory (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    memory_type varchar(24) not null,
    importance varchar(24) not null,
    title varchar(160) not null,
    summary clob not null,
    occurred_turn integer not null,
    world_time bigint not null,
    status varchar(24) not null,
    created_at timestamp not null,
    constraint fk_story_memory_save foreign key (save_id) references story_save(id) on delete cascade
);

create table story_conversation (
    save_id varchar(80) primary key,
    rolling_summary clob not null,
    summarized_through_turn integer not null default 0,
    summary_schema_version integer not null default 1,
    updated_at timestamp not null,
    constraint fk_story_conversation_save foreign key (save_id) references story_save(id) on delete cascade
);

create table story_state_correction (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    user_id varchar(64) not null,
    instruction_text clob not null,
    correction_type varchar(32) not null,
    state_schema_version integer not null,
    before_json clob not null,
    after_json clob not null,
    created_at timestamp not null,
    constraint fk_story_correction_save foreign key (save_id) references story_save(id) on delete cascade,
    constraint fk_story_correction_user foreign key (user_id) references app_user(id) on delete cascade
);

create table story_save_migration (
    id varchar(80) primary key,
    source_engine varchar(40) not null,
    source_save_id varchar(80) not null,
    target_engine varchar(40) not null,
    target_save_id varchar(80),
    source_schema_version integer not null,
    target_schema_version integer not null,
    status varchar(24) not null,
    before_hash varchar(128) not null,
    after_hash varchar(128),
    error_message varchar(1000),
    started_at timestamp,
    completed_at timestamp,
    unique(source_engine, source_save_id),
    constraint fk_story_migration_target foreign key (target_save_id) references story_save(id)
);

create table user_model_preference (
    user_id varchar(64) primary key,
    provider_code varchar(32) not null,
    model_code varchar(80) not null,
    credential_source varchar(20) not null,
    updated_at timestamp not null,
    constraint fk_user_model_preference_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_user_model_preference_model foreign key (provider_code, model_code)
        references ai_provider_model(provider_code, model_code)
);

create table analytics_event (
    id varchar(80) primary key,
    event_key varchar(180) not null unique,
    event_type varchar(48) not null,
    user_id varchar(64) not null,
    save_id varchar(80),
    release_version varchar(32) not null,
    engine_code varchar(48) not null,
    data_origin varchar(16) not null,
    metadata_json clob,
    occurred_at timestamp not null,
    recorded_at timestamp not null
);

create table analytics_user_day (
    activity_date date not null,
    user_id varchar(64) not null,
    created_save_count integer not null default 0,
    successful_turn_count integer not null default 0,
    correction_count integer not null default 0,
    entity_removal_count integer not null default 0,
    archived_save_count integer not null default 0,
    first_activity_at timestamp not null,
    last_activity_at timestamp not null,
    first_release_version varchar(32) not null,
    last_release_version varchar(32) not null,
    data_origin varchar(16) not null,
    updated_at timestamp not null,
    primary key (activity_date, user_id)
);

create table analytics_daily_metric (
    metric_date date not null,
    metric_code varchar(64) not null,
    dimension_type varchar(32) not null,
    dimension_value varchar(120) not null,
    metric_value decimal(24,4) not null,
    data_origin varchar(16) not null,
    calculation_version integer not null,
    calculated_at timestamp not null,
    primary key (metric_date, metric_code, dimension_type, dimension_value)
);

create table analytics_retention_cohort (
    cohort_date date not null,
    retention_day smallint not null,
    registered_users integer not null,
    activated_users integer not null,
    retained_users integer not null,
    data_origin varchar(16) not null,
    calculation_version integer not null,
    calculated_at timestamp not null,
    primary key (cohort_date, retention_day)
);

-- 世界包注册表；前置：MySQL 8.0.16+、app_user 存在且 ID 为 varchar(64) utf8mb4_unicode_ci。
-- 仅新增表，不发布、不启用、不修改命卷。已有环境先只读 preflight；本脚本不允许整体重复执行。
-- DDL 非事务性；中断后核对已建结构，仅继续缺失语句。回滚保留新增表，不自动删除内容。
create table world_package (
    id varchar(64) primary key,
    publisher_namespace varchar(128) not null,
    package_key varchar(128) not null,
    owner_user_id varchar(64),
    source_type varchar(16) not null,
    visibility varchar(16) not null,
    status varchar(16) not null,
    latest_revision bigint not null default 0,
    created_at timestamp(6) not null default current_timestamp(6),
    updated_at timestamp(6) not null default current_timestamp(6),
    constraint uk_world_package_identity unique (publisher_namespace, package_key),
    constraint fk_world_package_owner foreign key (owner_user_id) references app_user(id) on delete set null,
    constraint ck_world_package_revision check (latest_revision >= 0),
    constraint ck_world_package_source check (source_type in ('OFFICIAL','USER','IMPORTED')),
    constraint ck_world_package_visibility check (visibility in ('PRIVATE','UNLISTED','PUBLIC')),
    constraint ck_world_package_status check (status in ('ACTIVE','WITHDRAWN','SUSPENDED'))
);

create table world_package_revision (
    id varchar(64) primary key,
    package_id varchar(64) not null,
    revision bigint not null,
    format_version integer not null,
    runtime_contract varchar(80) not null,
    required_capabilities_json longtext not null,
    content_hash char(64) not null,
    compiled_hash char(64) not null,
    canonical_json longtext not null,
    compiled_json longtext not null,
    compiler_version varchar(64) not null,
    status varchar(16) not null,
    created_by varchar(96) not null,
    published_at timestamp(6) not null default current_timestamp(6),
    constraint uk_world_revision_number unique (package_id, revision),
    constraint uk_world_revision_content unique (package_id, content_hash),
    constraint uk_world_revision_package unique (id, package_id),
    constraint fk_world_revision_package foreign key (package_id) references world_package(id),
    constraint ck_world_revision_number check (revision > 0),
    constraint ck_world_revision_status check (status in ('PUBLISHED','WITHDRAWN','SUSPENDED'))
);

create table world_package_asset (
    revision_id varchar(64) not null,
    asset_path varchar(240) not null,
    media_type varchar(64) not null,
    byte_size bigint not null,
    content_hash char(64) not null,
    asset_bytes blob not null,
    primary key (revision_id, asset_path),
    constraint fk_world_asset_revision foreign key (revision_id) references world_package_revision(id),
    constraint ck_world_asset_size check (byte_size > 0)
);

create table world_package_publication_request (
    package_id varchar(64) not null,
    principal_key varchar(96) not null,
    idempotency_key varchar(128) not null,
    request_hash char(64) not null,
    revision_id varchar(64) not null,
    created_at timestamp(6) not null default current_timestamp(6),
    primary key (package_id, principal_key, idempotency_key),
    constraint fk_world_request_result foreign key (revision_id, package_id) references world_package_revision(id, package_id)
);

create table world_package_installation (
    user_id varchar(64) not null,
    revision_id varchar(64) not null,
    installed_at timestamp(6) not null default current_timestamp(6),
    primary key (user_id, revision_id),
    constraint fk_world_install_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_world_install_revision foreign key (revision_id) references world_package_revision(id)
);

create table world_package_creation_scope (
    id varchar(80) primary key,
    user_id varchar(64),
    scope_type varchar(8) not null,
    constraint uk_world_scope_user unique (user_id),
    constraint fk_world_scope_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint ck_world_scope check ((scope_type='GLOBAL' and id='GLOBAL' and user_id is null) or (scope_type='USER' and id<>'GLOBAL' and user_id is not null))
);

create table world_package_availability (
    scope_id varchar(80) not null,
    revision_id varchar(64) not null,
    enabled_at timestamp(6) not null default current_timestamp(6),
    primary key (scope_id, revision_id),
    constraint fk_world_available_scope foreign key (scope_id) references world_package_creation_scope(id) on delete cascade,
    constraint fk_world_available_revision foreign key (revision_id) references world_package_revision(id)
);

create table world_package_creation_default (
    scope_id varchar(80) primary key,
    revision_id varchar(64) not null,
    updated_at timestamp(6) not null default current_timestamp(6),
    constraint fk_world_default_available foreign key (scope_id, revision_id) references world_package_availability(scope_id, revision_id) on delete cascade
);

create table world_package_audit (
    id varchar(64) primary key,
    actor_key varchar(96) not null,
    action varchar(32) not null,
    scope_id varchar(80),
    revision_id varchar(64),
    created_at timestamp(6) not null default current_timestamp(6),
    constraint fk_world_audit_revision foreign key (revision_id) references world_package_revision(id)
);
