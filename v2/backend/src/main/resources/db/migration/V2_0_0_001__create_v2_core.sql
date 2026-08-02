create table v2_content_package (
    id varchar(80) primary key,
    package_code varchar(80) not null,
    version varchar(32) not null,
    package_type varchar(32) not null,
    status varchar(24) not null,
    checksum varchar(128) not null,
    published_at timestamp(6),
    created_at timestamp(6) not null,
    unique (package_code, version)
);

create table v2_location_definition (
    id varchar(80) primary key,
    content_package_id varchar(80) not null,
    location_code varchar(80) not null,
    name varchar(120) not null,
    location_type varchar(32) not null,
    parent_location_id varchar(80),
    description text not null,
    active boolean not null default true,
    unique (content_package_id, location_code),
    constraint fk_v2_location_package foreign key (content_package_id) references v2_content_package(id),
    constraint fk_v2_location_parent foreign key (parent_location_id) references v2_location_definition(id)
);

create table v2_location_edge (
    id varchar(80) primary key,
    content_package_id varchar(80) not null,
    from_location_id varchar(80) not null,
    to_location_id varchar(80) not null,
    travel_mode varchar(32) not null,
    travel_minutes bigint not null,
    requirement_code varchar(80),
    bidirectional boolean not null default false,
    active boolean not null default true,
    unique (from_location_id, to_location_id, travel_mode),
    constraint fk_v2_edge_package foreign key (content_package_id) references v2_content_package(id),
    constraint fk_v2_edge_from foreign key (from_location_id) references v2_location_definition(id),
    constraint fk_v2_edge_to foreign key (to_location_id) references v2_location_definition(id)
);

create table v2_currency_definition (
    currency_code varchar(40) primary key,
    content_package_id varchar(80) not null,
    name varchar(80) not null,
    currency_group varchar(32) not null,
    minor_unit_scale integer not null default 0,
    reference_value decimal(30, 8),
    active boolean not null default true,
    constraint fk_v2_currency_package foreign key (content_package_id) references v2_content_package(id)
);

create table v2_item_definition (
    id varchar(80) primary key,
    content_package_id varchar(80) not null,
    item_code varchar(80) not null,
    name varchar(120) not null,
    item_type varchar(40) not null,
    stackable boolean not null default false,
    consumable boolean not null default false,
    tradeable boolean not null default true,
    description text not null,
    active boolean not null default true,
    unique (content_package_id, item_code),
    constraint fk_v2_item_definition_package foreign key (content_package_id) references v2_content_package(id)
);

create table v2_ability_definition (
    id varchar(80) primary key,
    content_package_id varchar(80) not null,
    ability_code varchar(80) not null,
    name varchar(120) not null,
    ability_type varchar(40) not null,
    race_scope varchar(24) not null default 'ANY',
    description text not null,
    active boolean not null default true,
    unique (content_package_id, ability_code),
    constraint fk_v2_ability_definition_package foreign key (content_package_id) references v2_content_package(id)
);

create table v2_price_anchor (
    id varchar(80) primary key,
    content_package_id varchar(80) not null,
    location_id varchar(80) not null,
    item_definition_id varchar(80) not null,
    currency_code varchar(40) not null,
    minimum_amount decimal(30, 8) not null,
    typical_amount decimal(30, 8) not null,
    maximum_amount decimal(30, 8) not null,
    valid_from_world_minute bigint,
    valid_to_world_minute bigint,
    unique (content_package_id, location_id, item_definition_id, currency_code),
    constraint fk_v2_price_package foreign key (content_package_id) references v2_content_package(id),
    constraint fk_v2_price_location foreign key (location_id) references v2_location_definition(id),
    constraint fk_v2_price_item foreign key (item_definition_id) references v2_item_definition(id),
    constraint fk_v2_price_currency foreign key (currency_code) references v2_currency_definition(currency_code)
);

create table v2_game_save (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    slot_no smallint not null,
    status varchar(24) not null,
    difficulty varchar(24) not null,
    protagonist_id varchar(80),
    engine_version varchar(32) not null,
    content_version varchar(64) not null,
    state_version bigint not null default 0,
    turn_number integer not null default 0,
    end_reason varchar(40),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    ended_at timestamp(6),
    unique (user_id, slot_no),
    constraint fk_v2_save_user foreign key (user_id) references app_user(id) on delete cascade
);

create index idx_v2_save_user_status on v2_game_save(user_id, status, updated_at);

create table v2_content_binding (
    save_id varchar(80) not null,
    content_package_id varchar(80) not null,
    bound_version varchar(32) not null,
    bound_at timestamp(6) not null,
    primary key (save_id, content_package_id),
    constraint fk_v2_binding_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_binding_package foreign key (content_package_id) references v2_content_package(id)
);

create table v2_world_clock (
    save_id varchar(80) primary key,
    current_world_minute bigint not null,
    updated_at timestamp(6) not null,
    constraint fk_v2_clock_save foreign key (save_id) references v2_game_save(id) on delete cascade
);

create table v2_character (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    character_kind varchar(24) not null,
    name varchar(120) not null,
    gender_expression varchar(32) not null,
    race varchar(24) not null,
    root_type varchar(64) not null,
    root_description varchar(600) not null,
    life_goal varchar(300) not null,
    status varchar(24) not null,
    birth_world_minute bigint,
    created_world_minute bigint not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_v2_character_save foreign key (save_id) references v2_game_save(id) on delete cascade
);

create index idx_v2_character_save_kind on v2_character(save_id, character_kind, status);
create index idx_v2_character_save_name on v2_character(save_id, name);

create table v2_character_state (
    character_id varchar(80) primary key,
    location_id varchar(80) not null,
    life_stage_code varchar(40) not null,
    realm_code varchar(40) not null,
    realm_display_name varchar(80) not null,
    realm_phase varchar(24) not null,
    public_identity varchar(160) not null,
    body_condition varchar(200) not null,
    current_goal varchar(300) not null,
    state_version bigint not null default 0,
    updated_at timestamp(6) not null,
    constraint fk_v2_character_state_character foreign key (character_id) references v2_character(id) on delete cascade,
    constraint fk_v2_character_state_location foreign key (location_id) references v2_location_definition(id)
);

create table v2_save_location_state (
    save_id varchar(80) not null,
    location_id varchar(80) not null,
    known boolean not null default false,
    accessible boolean not null default false,
    controlling_faction_id varchar(80),
    local_state varchar(80) not null default 'STABLE',
    discovered_world_minute bigint,
    state_version bigint not null default 0,
    updated_at timestamp(6) not null,
    primary key (save_id, location_id),
    constraint fk_v2_save_location_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_save_location_definition foreign key (location_id) references v2_location_definition(id)
);

create table v2_relationship (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    source_character_id varchar(80) not null,
    target_character_id varchar(80) not null,
    relationship_type varchar(40) not null,
    trust_level smallint not null default 0,
    closeness_level smallint not null default 0,
    fear_level smallint not null default 0,
    interest_level smallint not null default 0,
    debt_summary varchar(500),
    conflict_summary varchar(500),
    shared_secret_summary varchar(500),
    last_changed_world_minute bigint not null,
    state_version bigint not null default 0,
    updated_at timestamp(6) not null,
    unique (save_id, source_character_id, target_character_id, relationship_type),
    constraint fk_v2_relationship_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_relationship_source foreign key (source_character_id) references v2_character(id) on delete cascade,
    constraint fk_v2_relationship_target foreign key (target_character_id) references v2_character(id) on delete cascade
);

create table v2_inventory (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    owner_character_id varchar(80) not null,
    inventory_type varchar(32) not null default 'PERSONAL',
    created_at timestamp(6) not null,
    unique (save_id, owner_character_id, inventory_type),
    constraint fk_v2_inventory_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_inventory_owner foreign key (owner_character_id) references v2_character(id) on delete cascade
);

create table v2_item_instance (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    inventory_id varchar(80) not null,
    item_definition_id varchar(80) not null,
    custom_name varchar(120),
    quantity decimal(30, 8) not null,
    item_status varchar(24) not null default 'AVAILABLE',
    condition_summary varchar(300) not null default '完好',
    provenance_summary varchar(600) not null,
    acquired_world_minute bigint not null,
    state_version bigint not null default 0,
    updated_at timestamp(6) not null,
    constraint fk_v2_item_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_item_inventory foreign key (inventory_id) references v2_inventory(id) on delete cascade,
    constraint fk_v2_item_definition foreign key (item_definition_id) references v2_item_definition(id)
);

create index idx_v2_item_inventory_status on v2_item_instance(inventory_id, item_status);

create table v2_item_event (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    item_instance_id varchar(80),
    event_type varchar(32) not null,
    quantity_change decimal(30, 8) not null default 0,
    from_owner_id varchar(80),
    to_owner_id varchar(80),
    source_turn_id varchar(80),
    reason varchar(500) not null,
    world_minute bigint not null,
    created_at timestamp(6) not null,
    constraint fk_v2_item_event_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_item_event_item foreign key (item_instance_id) references v2_item_instance(id) on delete set null
);

create index idx_v2_item_event_item_time on v2_item_event(item_instance_id, world_minute);

create table v2_wallet (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    owner_character_id varchar(80) not null,
    currency_code varchar(40) not null,
    balance decimal(30, 8) not null default 0,
    state_version bigint not null default 0,
    updated_at timestamp(6) not null,
    unique (save_id, owner_character_id, currency_code),
    constraint fk_v2_wallet_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_wallet_owner foreign key (owner_character_id) references v2_character(id) on delete cascade,
    constraint fk_v2_wallet_currency foreign key (currency_code) references v2_currency_definition(currency_code)
);

create table v2_wallet_ledger (
    id varchar(80) primary key,
    wallet_id varchar(80) not null,
    save_id varchar(80) not null,
    source_turn_id varchar(80),
    transaction_type varchar(32) not null,
    amount decimal(30, 8) not null,
    balance_before decimal(30, 8) not null,
    balance_after decimal(30, 8) not null,
    counterparty_character_id varchar(80),
    reason varchar(500) not null,
    world_minute bigint not null,
    created_at timestamp(6) not null,
    constraint fk_v2_wallet_ledger_wallet foreign key (wallet_id) references v2_wallet(id) on delete cascade,
    constraint fk_v2_wallet_ledger_save foreign key (save_id) references v2_game_save(id) on delete cascade
);

create index idx_v2_wallet_ledger_wallet_time on v2_wallet_ledger(wallet_id, world_minute);

create table v2_character_ability (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    character_id varchar(80) not null,
    ability_definition_id varchar(80) not null,
    source_type varchar(40) not null,
    source_reference_id varchar(80),
    mastery_stage varchar(32) not null,
    stability varchar(32) not null,
    ability_status varchar(24) not null default 'ACTIVE',
    learned_world_minute bigint not null,
    last_used_world_minute bigint,
    state_version bigint not null default 0,
    updated_at timestamp(6) not null,
    unique (save_id, character_id, ability_definition_id),
    constraint fk_v2_character_ability_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_character_ability_character foreign key (character_id) references v2_character(id) on delete cascade,
    constraint fk_v2_character_ability_definition foreign key (ability_definition_id) references v2_ability_definition(id)
);

create table v2_cultivation (
    character_id varchar(80) primary key,
    save_id varchar(80) not null,
    path_code varchar(80),
    path_name varchar(120),
    current_realm_code varchar(40) not null,
    current_realm_phase varchar(24) not null,
    target_realm_code varchar(40),
    project_stage varchar(32) not null default 'DORMANT',
    insight_summary varchar(800),
    state_version bigint not null default 0,
    updated_at timestamp(6) not null,
    constraint fk_v2_cultivation_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_cultivation_character foreign key (character_id) references v2_character(id) on delete cascade
);

create table v2_cultivation_requirement (
    id varchar(80) primary key,
    character_id varchar(80) not null,
    requirement_type varchar(40) not null,
    description varchar(500) not null,
    status varchar(24) not null,
    satisfied_world_minute bigint,
    source_reference_id varchar(80),
    updated_at timestamp(6) not null,
    constraint fk_v2_cultivation_requirement_character foreign key (character_id) references v2_character(id) on delete cascade
);

create table v2_knowledge (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    owner_character_id varchar(80) not null,
    subject_type varchar(40) not null,
    subject_id varchar(80) not null,
    knowledge_type varchar(32) not null,
    summary varchar(1000) not null,
    source_type varchar(40) not null,
    source_reference_id varchar(80),
    confidence varchar(24) not null,
    learned_world_minute bigint not null,
    superseded_by varchar(80),
    constraint fk_v2_knowledge_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_knowledge_owner foreign key (owner_character_id) references v2_character(id) on delete cascade
);

create index idx_v2_knowledge_subject on v2_knowledge(save_id, owner_character_id, subject_type, subject_id);

create table v2_story_thread (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    thread_type varchar(40) not null,
    title varchar(160) not null,
    summary varchar(1000) not null,
    status varchar(24) not null,
    priority smallint not null default 0,
    opened_world_minute bigint not null,
    due_world_minute bigint,
    closed_world_minute bigint,
    source_reference_id varchar(80),
    updated_at timestamp(6) not null,
    constraint fk_v2_story_thread_save foreign key (save_id) references v2_game_save(id) on delete cascade
);

create table v2_world_event (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    template_code varchar(80) not null,
    title varchar(160) not null,
    event_scale varchar(24) not null,
    stage varchar(32) not null,
    location_id varchar(80),
    started_world_minute bigint not null,
    next_transition_world_minute bigint,
    ended_world_minute bigint,
    public_summary varchar(1000) not null,
    hidden_summary text,
    state_version bigint not null default 0,
    updated_at timestamp(6) not null,
    constraint fk_v2_event_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_event_location foreign key (location_id) references v2_location_definition(id)
);

create index idx_v2_event_schedule on v2_world_event(save_id, stage, next_transition_world_minute);

create table v2_event_participant (
    event_id varchar(80) not null,
    character_id varchar(80) not null,
    participant_role varchar(40) not null,
    involvement_status varchar(24) not null,
    joined_world_minute bigint not null,
    left_world_minute bigint,
    primary key (event_id, character_id, participant_role),
    constraint fk_v2_event_participant_event foreign key (event_id) references v2_world_event(id) on delete cascade,
    constraint fk_v2_event_participant_character foreign key (character_id) references v2_character(id) on delete cascade
);

create table v2_schedule (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    character_id varchar(80),
    event_id varchar(80),
    schedule_type varchar(40) not null,
    due_world_minute bigint not null,
    status varchar(24) not null,
    payload text,
    resolved_world_minute bigint,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    constraint fk_v2_schedule_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_schedule_character foreign key (character_id) references v2_character(id) on delete cascade,
    constraint fk_v2_schedule_event foreign key (event_id) references v2_world_event(id) on delete cascade
);

create index idx_v2_schedule_due on v2_schedule(save_id, status, due_world_minute);

create table v2_turn_operation (
    id varchar(80) primary key,
    request_id varchar(80) not null,
    save_id varchar(80) not null,
    user_id varchar(64) not null,
    expected_state_version bigint not null,
    operation_status varchar(24) not null,
    operation_stage varchar(32) not null,
    action_type varchar(40) not null,
    action_text text not null,
    action_payload text,
    result_turn_id varchar(80),
    public_error_code varchar(64),
    public_error_message varchar(800),
    problem_reference varchar(40),
    created_at timestamp(6) not null,
    started_at timestamp(6),
    completed_at timestamp(6),
    unique (save_id, request_id),
    constraint fk_v2_operation_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_operation_user foreign key (user_id) references app_user(id) on delete cascade
);

create index idx_v2_operation_user_status on v2_turn_operation(user_id, operation_status, created_at);

create table v2_turn (
    id varchar(80) primary key,
    operation_id varchar(80) not null unique,
    save_id varchar(80) not null,
    turn_number integer not null,
    player_action text not null,
    action_intent text not null,
    outcome_code varchar(40) not null,
    outcome_summary varchar(1000) not null,
    narrative_text text not null,
    choices_payload text not null,
    world_minute_before bigint not null,
    world_minute_after bigint not null,
    state_version_before bigint not null,
    state_version_after bigint not null,
    model_provider varchar(40),
    model_code varchar(120),
    created_at timestamp(6) not null,
    unique (save_id, turn_number),
    constraint fk_v2_turn_operation foreign key (operation_id) references v2_turn_operation(id) on delete cascade,
    constraint fk_v2_turn_save foreign key (save_id) references v2_game_save(id) on delete cascade
);

create table v2_domain_change (
    id varchar(80) primary key,
    turn_id varchar(80) not null,
    save_id varchar(80) not null,
    change_order integer not null,
    domain_type varchar(40) not null,
    entity_id varchar(80) not null,
    change_type varchar(48) not null,
    before_payload text,
    after_payload text,
    reason varchar(500) not null,
    created_at timestamp(6) not null,
    unique (turn_id, change_order),
    constraint fk_v2_change_turn foreign key (turn_id) references v2_turn(id) on delete cascade,
    constraint fk_v2_change_save foreign key (save_id) references v2_game_save(id) on delete cascade
);

create table v2_story_summary (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    summary_type varchar(40) not null,
    subject_id varchar(80) not null,
    from_turn_number integer not null,
    to_turn_number integer not null,
    summary_text text not null,
    facts_payload text not null,
    model_provider varchar(40),
    model_code varchar(120),
    updated_at timestamp(6) not null,
    unique (save_id, summary_type, subject_id, to_turn_number),
    constraint fk_v2_summary_save foreign key (save_id) references v2_game_save(id) on delete cascade
);

create table v2_generated_entity (
    id varchar(80) primary key,
    save_id varchar(80) not null,
    entity_type varchar(40) not null,
    canonical_name varchar(160) not null,
    entity_status varchar(24) not null,
    definition_payload text not null,
    source_turn_id varchar(80),
    first_seen_world_minute bigint not null,
    state_version bigint not null default 0,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    unique (save_id, entity_type, canonical_name),
    constraint fk_v2_generated_entity_save foreign key (save_id) references v2_game_save(id) on delete cascade,
    constraint fk_v2_generated_entity_turn foreign key (source_turn_id) references v2_turn(id) on delete set null
);

create index idx_v2_generated_entity_type on v2_generated_entity(save_id, entity_type, entity_status);

create table ai_provider_config (
    provider_code varchar(40) primary key,
    display_name varchar(80) not null,
    official_base_url varchar(300) not null,
    active boolean not null default false,
    updated_at timestamp(6) not null
);

create table ai_model_catalog (
    model_code varchar(120) primary key,
    provider_code varchar(40) not null,
    display_name varchar(120) not null,
    capability_profile text not null,
    active boolean not null default false,
    updated_at timestamp(6) not null,
    constraint fk_ai_model_provider foreign key (provider_code) references ai_provider_config(provider_code)
);

create table ai_task_route (
    task_type varchar(40) primary key,
    provider_code varchar(40) not null,
    primary_model_code varchar(120) not null,
    fallback_model_code varchar(120),
    updated_at timestamp(6) not null,
    constraint fk_ai_route_provider foreign key (provider_code) references ai_provider_config(provider_code),
    constraint fk_ai_route_primary foreign key (primary_model_code) references ai_model_catalog(model_code),
    constraint fk_ai_route_fallback foreign key (fallback_model_code) references ai_model_catalog(model_code)
);

create table user_ai_provider_credential (
    user_id varchar(64) not null,
    provider_code varchar(40) not null,
    api_key_ciphertext text not null,
    masked_suffix varchar(12) not null,
    credential_status varchar(24) not null,
    last_verified_at timestamp(6),
    last_error_code varchar(64),
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    primary key (user_id, provider_code),
    constraint fk_user_provider_credential_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_user_provider_credential_provider foreign key (provider_code) references ai_provider_config(provider_code)
);

create table user_ai_preference (
    user_id varchar(64) primary key,
    credential_source varchar(24) not null,
    provider_code varchar(40) not null,
    model_code varchar(120) not null,
    updated_at timestamp(6) not null,
    constraint fk_user_ai_preference_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_user_ai_preference_provider foreign key (provider_code) references ai_provider_config(provider_code),
    constraint fk_user_ai_preference_model foreign key (model_code) references ai_model_catalog(model_code)
);

create table v2_ai_usage_log (
    id varchar(80) primary key,
    user_id varchar(64) not null,
    save_id varchar(80),
    operation_id varchar(80),
    task_type varchar(40) not null,
    provider_code varchar(40) not null,
    model_code varchar(120) not null,
    credential_source varchar(24) not null,
    prompt_tokens integer not null default 0,
    completion_tokens integer not null default 0,
    latency_millis bigint not null default 0,
    result_status varchar(24) not null,
    provider_request_id varchar(160),
    error_code varchar(64),
    created_at timestamp(6) not null,
    constraint fk_ai_usage_user foreign key (user_id) references app_user(id) on delete cascade,
    constraint fk_ai_usage_save foreign key (save_id) references v2_game_save(id) on delete set null,
    constraint fk_ai_usage_operation foreign key (operation_id) references v2_turn_operation(id) on delete set null,
    constraint fk_ai_usage_provider foreign key (provider_code) references ai_provider_config(provider_code),
    constraint fk_ai_usage_model foreign key (model_code) references ai_model_catalog(model_code)
);

create index idx_v2_ai_usage_user_time on v2_ai_usage_log(user_id, created_at);
create index idx_v2_ai_usage_model_time on v2_ai_usage_log(provider_code, model_code, created_at);
