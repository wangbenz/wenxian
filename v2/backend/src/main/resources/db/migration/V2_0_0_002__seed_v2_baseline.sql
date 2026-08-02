insert into v2_content_package(id, package_code, version, package_type, status, checksum, published_at, created_at)
values ('content-xuanyu-core-020', 'xuanyu-core', '0.2.0', 'CANON', 'ACTIVE', 'DEV-XUANYU-020', current_timestamp, current_timestamp);

insert into v2_content_package(id, package_code, version, package_type, status, checksum, published_at, created_at)
values ('content-qingqi-020', 'qingqi', '0.2.0', 'REGION', 'ACTIVE', 'DEV-QINGQI-020', current_timestamp, current_timestamp);

insert into v2_location_definition(id, content_package_id, location_code, name, location_type, parent_location_id, description)
values ('loc-xuanyu', 'content-xuanyu-core-020', 'XUANYU', '玄墟界', 'WORLD', null, '绝天之变后三千年的修行世界。');
insert into v2_location_definition(id, content_package_id, location_code, name, location_type, parent_location_id, description)
values ('loc-eastland', 'content-xuanyu-core-020', 'EASTERN_LANDS', '东陆九州', 'DOMAIN', 'loc-xuanyu', '王朝、城镇、宗门与商路交织之地。');
insert into v2_location_definition(id, content_package_id, location_code, name, location_type, parent_location_id, description)
values ('loc-qingqi', 'content-qingqi-020', 'QINGQI', '青岐州', 'REGION', 'loc-eastland', '东陆与北荒交界的边州，是V0.2首个长期地域。');
insert into v2_location_definition(id, content_package_id, location_code, name, location_type, parent_location_id, description)
values ('loc-qingmu-town', 'content-qingqi-020', 'QINGMU_TOWN', '青木镇', 'SETTLEMENT', 'loc-qingqi', '依矿路而生的小镇，凡俗官府、商户和修行耳目并存。');
insert into v2_location_definition(id, content_package_id, location_code, name, location_type, parent_location_id, description)
values ('loc-qingmu-east-gate', 'content-qingqi-020', 'QINGMU_EAST_GATE', '青木镇东门', 'SCENE', 'loc-qingmu-town', '商旅与流民进入青木镇最常经过的城门。');
insert into v2_location_definition(id, content_package_id, location_code, name, location_type, parent_location_id, description)
values ('loc-zhaogu-mine', 'content-qingqi-020', 'ZHAOGU_MINE', '照骨矿', 'SITE', 'loc-qingqi', '出产异矿的古老矿场，深处近期出现无法解释的震动。');
insert into v2_location_definition(id, content_package_id, location_code, name, location_type, parent_location_id, description)
values ('loc-huichun-hall', 'content-qingqi-020', 'HUICHUN_HALL', '回春堂', 'SITE', 'loc-qingmu-town', '经营凡药与低阶灵药的医馆和药铺。');
insert into v2_location_definition(id, content_package_id, location_code, name, location_type, parent_location_id, description)
values ('loc-bronze-gate', 'content-qingqi-020', 'BRONZE_GATE', '青铜门', 'SECT', 'loc-qingqi', '掌握附近部分灵脉的小宗门。');
insert into v2_location_definition(id, content_package_id, location_code, name, location_type, parent_location_id, description)
values ('loc-northern-road', 'content-qingqi-020', 'NORTHERN_POST_ROAD', '北境驿道', 'ROUTE', 'loc-qingqi', '通往北荒边缘与东陆北境的旧驿道。');

insert into v2_location_edge(id, content_package_id, from_location_id, to_location_id, travel_mode, travel_minutes, bidirectional)
values ('edge-eastgate-town', 'content-qingqi-020', 'loc-qingmu-east-gate', 'loc-qingmu-town', 'WALK', 20, true);
insert into v2_location_edge(id, content_package_id, from_location_id, to_location_id, travel_mode, travel_minutes, bidirectional)
values ('edge-town-hall', 'content-qingqi-020', 'loc-qingmu-town', 'loc-huichun-hall', 'WALK', 15, true);
insert into v2_location_edge(id, content_package_id, from_location_id, to_location_id, travel_mode, travel_minutes, bidirectional)
values ('edge-town-mine', 'content-qingqi-020', 'loc-qingmu-town', 'loc-zhaogu-mine', 'WALK', 240, true);
insert into v2_location_edge(id, content_package_id, from_location_id, to_location_id, travel_mode, travel_minutes, bidirectional)
values ('edge-town-bronze', 'content-qingqi-020', 'loc-qingmu-town', 'loc-bronze-gate', 'WALK', 360, true);
insert into v2_location_edge(id, content_package_id, from_location_id, to_location_id, travel_mode, travel_minutes, bidirectional)
values ('edge-town-road', 'content-qingqi-020', 'loc-qingmu-town', 'loc-northern-road', 'WALK', 180, true);

insert into v2_currency_definition(currency_code, content_package_id, name, currency_group, minor_unit_scale, reference_value)
values ('WEN', 'content-xuanyu-core-020', '文钱', 'MORTAL', 0, 1);
insert into v2_currency_definition(currency_code, content_package_id, name, currency_group, minor_unit_scale, reference_value)
values ('SILVER_LIANG', 'content-xuanyu-core-020', '银两', 'MORTAL', 0, 1000);
insert into v2_currency_definition(currency_code, content_package_id, name, currency_group, minor_unit_scale, reference_value)
values ('GOLD_LIANG', 'content-xuanyu-core-020', '金两', 'MORTAL', 0, 10000);
insert into v2_currency_definition(currency_code, content_package_id, name, currency_group, minor_unit_scale, reference_value)
values ('SPIRIT_STONE_LOW', 'content-xuanyu-core-020', '下品灵石', 'CULTIVATION', 0, 1);
insert into v2_currency_definition(currency_code, content_package_id, name, currency_group, minor_unit_scale, reference_value)
values ('SPIRIT_STONE_MID', 'content-xuanyu-core-020', '中品灵石', 'CULTIVATION', 0, 100);
insert into v2_currency_definition(currency_code, content_package_id, name, currency_group, minor_unit_scale, reference_value)
values ('SPIRIT_STONE_HIGH', 'content-xuanyu-core-020', '上品灵石', 'CULTIVATION', 0, 10000);

insert into v2_item_definition(id, content_package_id, item_code, name, item_type, stackable, consumable, tradeable, description)
values ('item-coarse-ration', 'content-qingqi-020', 'COARSE_RATION', '粗粮干饼', 'FOOD', true, true, true, '青岐州常见的赶路干粮。');
insert into v2_item_definition(id, content_package_id, item_code, name, item_type, stackable, consumable, tradeable, description)
values ('item-common-herb', 'content-qingqi-020', 'COMMON_HEALING_HERB', '止血草', 'HERB', true, true, true, '可用于处理普通外伤的常见草药。');
insert into v2_item_definition(id, content_package_id, item_code, name, item_type, stackable, consumable, tradeable, description)
values ('item-identity-token', 'content-qingqi-020', 'IDENTITY_TOKEN', '身世旧物', 'KEEPSAKE', false, false, false, '与角色根源相连的旧物，具体形态由命格决定。');

insert into v2_price_anchor(id, content_package_id, location_id, item_definition_id, currency_code, minimum_amount, typical_amount, maximum_amount)
values ('price-ration-qingmu', 'content-qingqi-020', 'loc-qingmu-town', 'item-coarse-ration', 'WEN', 2, 3, 6);
insert into v2_price_anchor(id, content_package_id, location_id, item_definition_id, currency_code, minimum_amount, typical_amount, maximum_amount)
values ('price-herb-qingmu', 'content-qingqi-020', 'loc-qingmu-town', 'item-common-herb', 'WEN', 8, 12, 24);

insert into ai_provider_config(provider_code, display_name, official_base_url, active, updated_at)
values ('DEEPSEEK', 'DeepSeek', 'https://api.deepseek.com', true, current_timestamp);
insert into ai_model_catalog(model_code, provider_code, display_name, capability_profile, active, updated_at)
values ('deepseek-v4-flash', 'DEEPSEEK', 'DeepSeek V4 Flash', '{"structuredOutput":true,"streaming":true}', true, current_timestamp);
insert into ai_model_catalog(model_code, provider_code, display_name, capability_profile, active, updated_at)
values ('deepseek-v4-pro', 'DEEPSEEK', 'DeepSeek V4 Pro', '{"structuredOutput":true,"streaming":true}', true, current_timestamp);
insert into ai_task_route(task_type, provider_code, primary_model_code, fallback_model_code, updated_at)
values ('ACTION_INTERPRETATION', 'DEEPSEEK', 'deepseek-v4-flash', 'deepseek-v4-pro', current_timestamp);
insert into ai_task_route(task_type, provider_code, primary_model_code, fallback_model_code, updated_at)
values ('STORY_PLANNING', 'DEEPSEEK', 'deepseek-v4-flash', 'deepseek-v4-pro', current_timestamp);
insert into ai_task_route(task_type, provider_code, primary_model_code, fallback_model_code, updated_at)
values ('NARRATIVE_WRITING', 'DEEPSEEK', 'deepseek-v4-flash', 'deepseek-v4-pro', current_timestamp);
insert into ai_task_route(task_type, provider_code, primary_model_code, fallback_model_code, updated_at)
values ('SUMMARY', 'DEEPSEEK', 'deepseek-v4-flash', null, current_timestamp);
