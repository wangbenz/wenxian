insert into v2_location_definition(id,content_package_id,location_code,name,location_type,parent_location_id,description)
values ('loc-qingmu-market','content-qingqi-020','QINGMU_MARKET','青木集市','MARKET','loc-qingmu-town','凡货、矿料与低阶修行物资交汇的露天集市。');
insert into v2_location_definition(id,content_package_id,location_code,name,location_type,parent_location_id,description)
values ('loc-herb-ridge','content-qingqi-020','HERB_RIDGE','百草岭','WILDERNESS','loc-qingqi','回春堂药农与散修共同采药的低山，越过标记后风险显著增加。');
insert into v2_location_definition(id,content_package_id,location_code,name,location_type,parent_location_id,description)
values ('loc-reed-ferry','content-qingqi-020','REED_FERRY','芦水渡','ROUTE','loc-qingqi','连接青岐州南北货路的小渡口，雨季常因水势停航。');
insert into v2_location_definition(id,content_package_id,location_code,name,location_type,parent_location_id,description)
values ('loc-abandoned-kiln','content-qingqi-020','ABANDONED_KILN','废火窑','RUIN','loc-qingqi','照骨矿外废弃多年的炼矿窑，偶有拾荒人与低阶妖物出没。');
insert into v2_location_definition(id,content_package_id,location_code,name,location_type,parent_location_id,description)
values ('loc-bronze-outer-court','content-qingqi-020','BRONZE_OUTER_COURT','青铜门外院','SECT_SITE','loc-bronze-gate','青铜门接待杂役、商客与求学者的外院，不等同于正式入门。');

insert into v2_location_edge(id,content_package_id,from_location_id,to_location_id,travel_mode,travel_minutes,bidirectional)
values ('edge-town-market','content-qingqi-020','loc-qingmu-town','loc-qingmu-market','WALK',10,true);
insert into v2_location_edge(id,content_package_id,from_location_id,to_location_id,travel_mode,travel_minutes,bidirectional)
values ('edge-town-herb','content-qingqi-020','loc-qingmu-town','loc-herb-ridge','WALK',150,true);
insert into v2_location_edge(id,content_package_id,from_location_id,to_location_id,travel_mode,travel_minutes,bidirectional)
values ('edge-town-ferry','content-qingqi-020','loc-qingmu-town','loc-reed-ferry','WALK',120,true);
insert into v2_location_edge(id,content_package_id,from_location_id,to_location_id,travel_mode,travel_minutes,bidirectional)
values ('edge-mine-kiln','content-qingqi-020','loc-zhaogu-mine','loc-abandoned-kiln','WALK',45,true);
insert into v2_location_edge(id,content_package_id,from_location_id,to_location_id,travel_mode,travel_minutes,bidirectional)
values ('edge-bronze-outer','content-qingqi-020','loc-bronze-gate','loc-bronze-outer-court','WALK',20,true);

insert into v2_item_definition(id,content_package_id,item_code,name,item_type,stackable,consumable,tradeable,description)
values ('item-lamp-oil','content-qingqi-020','LAMP_OIL','灯油','SUPPLY',true,true,true,'可供普通油灯燃烧数个时辰的桐油。');
insert into v2_item_definition(id,content_package_id,item_code,name,item_type,stackable,consumable,tradeable,description)
values ('item-hemp-rope','content-qingqi-020','HEMP_ROPE','麻绳','TOOL',true,false,true,'矿工与行脚人常备的结实麻绳。');
insert into v2_item_definition(id,content_package_id,item_code,name,item_type,stackable,consumable,tradeable,description)
values ('item-herb-decoction','content-qingqi-020','HERB_DECOCTION','回气药汤','MEDICINE',true,true,true,'回春堂熬制的凡药，只能缓解普通疲惫与轻伤。');
insert into v2_item_definition(id,content_package_id,item_code,name,item_type,stackable,consumable,tradeable,description)
values ('item-iron-chisel','content-qingqi-020','IRON_CHISEL','铁钎','TOOL',false,false,true,'照骨矿常用的短柄铁钎，可开凿普通岩层。');
insert into v2_item_definition(id,content_package_id,item_code,name,item_type,stackable,consumable,tradeable,description)
values ('item-low-spirit-sand','content-qingqi-020','LOW_SPIRIT_SAND','碎灵砂','CULTIVATION_RESOURCE',true,true,true,'从废矿中筛出的微量灵砂，远不及完整灵石。');
insert into v2_item_definition(id,content_package_id,item_code,name,item_type,stackable,consumable,tradeable,description)
values ('item-ward-paper','content-qingqi-020','WARD_PAPER','避祟纸','TALISMAN',true,true,true,'青铜门外院出售的低阶符纸，只能短暂驱散微弱阴秽。');
insert into v2_item_definition(id,content_package_id,item_code,name,item_type,stackable,consumable,tradeable,description)
values ('item-beast-hide','content-qingqi-020','BEAST_HIDE','山兽皮','MATERIAL',true,false,true,'北境常见山兽的皮料，可卖给皮匠或行商。');

insert into v2_price_anchor(id,content_package_id,location_id,item_definition_id,currency_code,minimum_amount,typical_amount,maximum_amount)
values ('price-ration-qingqi','content-qingqi-020','loc-qingqi','item-coarse-ration','WEN',2,4,8);
insert into v2_price_anchor(id,content_package_id,location_id,item_definition_id,currency_code,minimum_amount,typical_amount,maximum_amount)
values ('price-herb-qingqi','content-qingqi-020','loc-qingqi','item-common-herb','WEN',6,14,30);
insert into v2_price_anchor(id,content_package_id,location_id,item_definition_id,currency_code,minimum_amount,typical_amount,maximum_amount)
values ('price-oil-qingqi','content-qingqi-020','loc-qingqi','item-lamp-oil','WEN',4,8,16);
insert into v2_price_anchor(id,content_package_id,location_id,item_definition_id,currency_code,minimum_amount,typical_amount,maximum_amount)
values ('price-rope-qingqi','content-qingqi-020','loc-qingqi','item-hemp-rope','WEN',12,20,36);
insert into v2_price_anchor(id,content_package_id,location_id,item_definition_id,currency_code,minimum_amount,typical_amount,maximum_amount)
values ('price-decoction-qingqi','content-qingqi-020','loc-qingqi','item-herb-decoction','WEN',25,40,70);
insert into v2_price_anchor(id,content_package_id,location_id,item_definition_id,currency_code,minimum_amount,typical_amount,maximum_amount)
values ('price-chisel-qingqi','content-qingqi-020','loc-qingqi','item-iron-chisel','WEN',35,55,90);
insert into v2_price_anchor(id,content_package_id,location_id,item_definition_id,currency_code,minimum_amount,typical_amount,maximum_amount)
values ('price-sand-qingqi','content-qingqi-020','loc-qingqi','item-low-spirit-sand','WEN',80,120,220);
insert into v2_price_anchor(id,content_package_id,location_id,item_definition_id,currency_code,minimum_amount,typical_amount,maximum_amount)
values ('price-ward-qingqi','content-qingqi-020','loc-qingqi','item-ward-paper','WEN',90,150,260);
insert into v2_price_anchor(id,content_package_id,location_id,item_definition_id,currency_code,minimum_amount,typical_amount,maximum_amount)
values ('price-hide-qingqi','content-qingqi-020','loc-qingqi','item-beast-hide','WEN',25,45,90);

insert into v2_ability_definition(id,content_package_id,ability_code,name,ability_type,race_scope,description,active)
values ('ability-herb-identification','content-qingqi-020','HERB_IDENTIFICATION','辨草','SKILL','ANY','辨认青岐州常见药草、毒草与采摘时机的生计技艺。',true);
insert into v2_ability_definition(id,content_package_id,ability_code,name,ability_type,race_scope,description,active)
values ('ability-mining-craft','content-qingqi-020','MINING_CRAFT','识矿','SKILL','ANY','观察石色、纹理与敲击声，分辨普通矿料和危险空层。',true);
insert into v2_ability_definition(id,content_package_id,ability_code,name,ability_type,race_scope,description,active)
values ('ability-market-haggling','content-qingqi-020','MARKET_HAGGLING','识价','SKILL','ANY','根据货色、季节和市面供求判断合理价格，不能强迫商贩让利。',true);
insert into v2_ability_definition(id,content_package_id,ability_code,name,ability_type,race_scope,description,active)
values ('ability-light-step','content-qingqi-020','LIGHT_STEP','轻身步','TECHNIQUE','ANY','青岐州常见的入门身法，需要长期练习，初识时只能改善崎岖地面的落脚。',true);
insert into v2_ability_definition(id,content_package_id,ability_code,name,ability_type,race_scope,description,active)
values ('ability-conceal-aura','content-qingqi-020','CONCEAL_AURA','敛息诀','TECHNIQUE','ANY','收束自身气息的低阶法门，无法瞒过明显高于自己的主动探查。',true);
insert into v2_ability_definition(id,content_package_id,ability_code,name,ability_type,race_scope,description,active)
values ('ability-field-mending','content-qingqi-020','FIELD_MENDING','行路缝补','SKILL','ANY','修补衣物、行囊与简易绳结的日常技艺。',true);

insert into v2_event_template(id,content_package_id,template_code,title,event_scale,initial_stage,description,default_transition_minutes,active,created_at,updated_at)
values ('event-template-qingqi-medicine-shortage','content-qingqi-020','QINGQI_MEDICINE_SHORTAGE','青木药荒','LOCAL','SEED','百草岭采药量下降，回春堂与行商会随时间调整收购与售价。',129600,true,current_timestamp,current_timestamp);
insert into v2_event_template(id,content_package_id,template_code,title,event_scale,initial_stage,description,default_transition_minutes,active,created_at,updated_at)
values ('event-template-qingqi-caravan-delay','content-qingqi-020','QINGQI_CARAVAN_DELAY','北路商队迟滞','REGIONAL','SEED','北境驿道上的商队连续误期，原因可能来自天气、盗匪或妖群迁徙。',172800,true,current_timestamp,current_timestamp);
insert into v2_event_template(id,content_package_id,template_code,title,event_scale,initial_stage,description,default_transition_minutes,active,created_at,updated_at)
values ('event-template-bronze-recruitment','content-qingqi-020','BRONZE_GATE_RECRUITMENT','青铜门外院招募','LOCAL','SEED','青铜门周期性招收杂役、客工与记名学徒，入门并不等于得到仙缘。',259200,true,current_timestamp,current_timestamp);
