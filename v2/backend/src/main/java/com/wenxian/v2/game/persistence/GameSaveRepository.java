package com.wenxian.v2.game.persistence;

import com.wenxian.v2.game.domain.Difficulty;
import com.wenxian.v2.game.domain.GameRecords.Ability;
import com.wenxian.v2.game.domain.GameRecords.Character;
import com.wenxian.v2.game.domain.GameRecords.CharacterOrigin;
import com.wenxian.v2.game.domain.GameRecords.CharacterState;
import com.wenxian.v2.game.domain.GameRecords.CultivationProgress;
import com.wenxian.v2.game.domain.GameRecords.InventoryItem;
import com.wenxian.v2.game.domain.GameRecords.KnownCharacter;
import com.wenxian.v2.game.domain.GameRecords.LifeEnding;
import com.wenxian.v2.game.domain.GameRecords.Relationship;
import com.wenxian.v2.game.domain.GameRecords.Save;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.domain.GameRecords.WalletBalance;
import com.wenxian.v2.game.domain.GameRecords.WorldEvent;
import com.wenxian.v2.game.domain.Race;
import com.wenxian.v2.game.domain.WorldCalendar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class GameSaveRepository {
    private final JdbcTemplate jdbc;

    public GameSaveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockUser(String userId) {
        jdbc.queryForObject("select id from app_user where id=? for update", String.class, userId);
    }

    public int activeSaveCount(String userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from v2_game_save where user_id=? and status in ('CREATING','ACTIVE')",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    public int nextSlotNo(String userId) {
        Integer slot = jdbc.queryForObject(
                "select coalesce(max(slot_no),0)+1 from v2_game_save where user_id=?", Integer.class, userId);
        return slot == null ? 1 : slot;
    }

    public void insertSave(Save save) {
        jdbc.update("""
                insert into v2_game_save(id,user_id,slot_no,status,difficulty,protagonist_id,
                                         engine_version,content_version,state_version,turn_number,
                                         created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, save.id(), save.userId(), save.slotNo(), save.status(), save.difficulty().name(),
                save.protagonistId(), save.engineVersion(), save.contentVersion(), save.stateVersion(),
                save.turnNumber(), Timestamp.from(save.createdAt()), Timestamp.from(save.updatedAt()));
    }

    public void insertWorldClock(String saveId, long worldMinute, Instant now) {
        jdbc.update("insert into v2_world_clock(save_id,current_world_minute,updated_at) values (?,?,?)",
                saveId, worldMinute, Timestamp.from(now));
    }

    public void insertCharacter(Character character, long createdWorldMinute, Instant now) {
        jdbc.update("""
                insert into v2_character(id,save_id,character_kind,name,gender_expression,race,root_type,
                                         root_description,life_goal,status,birth_world_minute,
                                         created_world_minute,created_at,updated_at)
                values (?,?,'PROTAGONIST',?,?,?,?,?,?,?,?,?,?,?)
                """, character.id(), character.saveId(), character.name(), character.genderExpression(),
                character.race().name(), character.rootType(), character.rootDescription(), character.lifeGoal(),
                character.status(), null, createdWorldMinute, Timestamp.from(now), Timestamp.from(now));
    }

    public void insertCharacterState(CharacterState state, Instant now) {
        jdbc.update("""
                insert into v2_character_state(character_id,location_id,life_stage_code,realm_code,
                                               realm_display_name,realm_phase,public_identity,body_condition,
                                               current_goal,state_version,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, state.characterId(), state.locationId(), state.lifeStageCode(), state.realmCode(),
                state.realmDisplayName(), state.realmPhase(), state.publicIdentity(), state.bodyCondition(),
                state.currentGoal(), state.stateVersion(), Timestamp.from(now));
    }

    public void insertCharacterOrigin(String characterId, String saveId, String fateTitle,
                                      String publicIdentity, String openingSituation,
                                      String unpaidDebt, Instant now) {
        jdbc.update("""
                insert into v2_character_origin(character_id,save_id,fate_title,public_identity,
                                                opening_situation,unpaid_debt,created_at)
                values (?,?,?,?,?,?,?)
                """, characterId, saveId, fateTitle, publicIdentity, openingSituation, unpaidDebt,
                Timestamp.from(now));
    }

    public void insertInventory(String inventoryId, String saveId, String characterId, Instant now) {
        jdbc.update("""
                insert into v2_inventory(id,save_id,owner_character_id,inventory_type,created_at)
                values (?,?,?,'PERSONAL',?)
                """, inventoryId, saveId, characterId, Timestamp.from(now));
    }

    public void insertStarterKeepsake(String saveId, String inventoryId, String characterId,
                                      String rootDescription, long worldMinute, Instant now) {
        String itemId = "v2-item-" + java.util.UUID.randomUUID();
        jdbc.update("""
                insert into v2_item_instance(id,save_id,inventory_id,item_definition_id,custom_name,quantity,
                                             item_status,condition_summary,provenance_summary,
                                             acquired_world_minute,state_version,updated_at)
                values (?,?,?,'item-identity-token','身世旧物',1,'AVAILABLE','完好',?,?,0,?)
                """, itemId, saveId, inventoryId, "与角色根源相连：" + rootDescription,
                worldMinute, Timestamp.from(now));
        jdbc.update("""
                insert into v2_item_event(id,save_id,item_instance_id,event_type,quantity_change,
                                          from_owner_id,to_owner_id,reason,world_minute,created_at)
                values (?,?,?,'ACQUIRED',1,null,?,'命卷创建时确认的身世旧物',?,?)
                """, "v2-item-event-" + java.util.UUID.randomUUID(), saveId, itemId, characterId,
                worldMinute, Timestamp.from(now));
    }

    public void insertCultivation(String saveId, String characterId, String realmCode, Instant now) {
        jdbc.update("""
                insert into v2_cultivation(character_id,save_id,current_realm_code,current_realm_phase,
                                           project_stage,state_version,updated_at)
                values (?,?,?,'INITIAL','DORMANT',0,?)
                """, characterId, saveId, realmCode, Timestamp.from(now));
    }

    public void insertWallets(String saveId, String characterId, Instant now) {
        for (String currency : List.of("WEN", "SILVER_LIANG", "GOLD_LIANG",
                "SPIRIT_STONE_LOW", "SPIRIT_STONE_MID", "SPIRIT_STONE_HIGH")) {
            jdbc.update("""
                    insert into v2_wallet(id,save_id,owner_character_id,currency_code,balance,state_version,updated_at)
                    values (?,?,?,?,0,0,?)
                    """, "wallet-" + java.util.UUID.randomUUID(), saveId, characterId, currency, Timestamp.from(now));
        }
    }

    public void bindBaseContent(String saveId, Instant now) {
        jdbc.update("""
                insert into v2_content_binding(save_id,content_package_id,bound_version,bound_at)
                values (?,'content-xuanyu-core-020','0.2.0',?)
                """, saveId, Timestamp.from(now));
        jdbc.update("""
                insert into v2_content_binding(save_id,content_package_id,bound_version,bound_at)
                values (?,'content-qingqi-020','0.2.0',?)
                """, saveId, Timestamp.from(now));
    }

    public void insertInitialWorldEvent(String saveId, long worldMinute, Instant now) {
        jdbc.update("""
                insert into v2_world_event(id,save_id,template_code,title,event_scale,stage,location_id,
                                           started_world_minute,next_transition_world_minute,public_summary,
                                           hidden_summary,state_version,updated_at)
                values (?,?, 'QINGQI_MINE_DISTURBANCE','照骨矿异动','REGIONAL','SEED','loc-zhaogu-mine',
                        ?,?,'照骨矿仍在照常出矿，少数矿工偶尔提到深处的闷响。',
                        '事件会自行发展，不要求玩家参与。',0,?)
                """, "v2-event-" + java.util.UUID.randomUUID(), saveId, worldMinute,
                worldMinute + 30L * WorldCalendar.MINUTES_PER_DAY, Timestamp.from(now));
        insertWorldEvent(saveId, "QINGQI_MEDICINE_SHORTAGE", "青木药荒", "LOCAL",
                "loc-huichun-hall", "百草岭的常用药草收成略少，回春堂尚未公开调整药价。",
                "药材供给会随时间影响采药人与病患。", worldMinute,
                worldMinute + 45L * WorldCalendar.MINUTES_PER_DAY, now);
        insertWorldEvent(saveId, "QINGQI_CARAVAN_DELAY", "北路商队迟滞", "REGIONAL",
                "loc-northern-road", "两支应在近日抵达青木镇的北路商队仍无消息。",
                "迟滞原因不会因为玩家未介入而停止发展。", worldMinute,
                worldMinute + 60L * WorldCalendar.MINUTES_PER_DAY, now);
        insertWorldEvent(saveId, "BRONZE_GATE_RECRUITMENT", "青铜门外院招募", "LOCAL",
                "loc-bronze-outer-court", "青铜门外院正在整理下一次招募名册，具体条件尚未张榜。",
                "招募是长期地域机会之一，并非主线强制事件。", worldMinute,
                worldMinute + 90L * WorldCalendar.MINUTES_PER_DAY, now);
    }

    private void insertWorldEvent(String saveId, String templateCode, String title, String scale,
                                  String locationId, String publicSummary, String hiddenSummary,
                                  long worldMinute, long nextMinute, Instant now) {
        jdbc.update("""
                insert into v2_world_event(id,save_id,template_code,title,event_scale,stage,location_id,
                                           started_world_minute,next_transition_world_minute,public_summary,
                                           hidden_summary,state_version,updated_at)
                values (?,?,?,?,?,'SEED',?,?,?,?,?,0,?)
                """, "v2-event-" + java.util.UUID.randomUUID(), saveId, templateCode, title, scale,
                locationId, worldMinute, nextMinute, publicSummary, hiddenSummary, Timestamp.from(now));
    }

    public void revealStartingLocations(String saveId, String currentLocationId, long worldMinute, Instant now) {
        java.util.Set<String> visible = new java.util.LinkedHashSet<>(
                List.of("loc-qingqi", "loc-qingmu-town", currentLocationId));
        visible.addAll(jdbc.query("""
                select distinct case when from_location_id in ('loc-qingmu-town',?)
                    then to_location_id else from_location_id end
                from v2_location_edge
                where active=true and bidirectional=true
                  and (from_location_id in ('loc-qingmu-town',?) or to_location_id in ('loc-qingmu-town',?))
                """, (row, index) -> row.getString(1), currentLocationId, currentLocationId, currentLocationId));
        for (String locationId : visible) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from v2_save_location_state where save_id=? and location_id=?
                    """, Integer.class, saveId, locationId);
            if (count != null && count > 0) continue;
            jdbc.update("""
                    insert into v2_save_location_state(save_id,location_id,known,accessible,local_state,
                                                       discovered_world_minute,state_version,updated_at)
                    values (?,?,true,true,'STABLE',?,0,?)
                    """, saveId, locationId, worldMinute, Timestamp.from(now));
        }
    }

    public List<Save> list(String userId) {
        return jdbc.query("""
                        select id,user_id,slot_no,status,difficulty,protagonist_id,engine_version,
                               content_version,state_version,turn_number,end_reason,created_at,updated_at,ended_at
                        from v2_game_save where user_id=? order by slot_no
                        """, (row, index) -> save(row), userId);
    }

    public Optional<Save> findOwned(String saveId, String userId) {
        return jdbc.query("""
                        select id,user_id,slot_no,status,difficulty,protagonist_id,engine_version,
                               content_version,state_version,turn_number,end_reason,created_at,updated_at,ended_at
                        from v2_game_save where id=? and user_id=?
                        """, (row, index) -> save(row), saveId, userId).stream().findFirst();
    }

    public Save lockOwned(String saveId, String userId) {
        return jdbc.query("""
                        select id,user_id,slot_no,status,difficulty,protagonist_id,engine_version,
                               content_version,state_version,turn_number,end_reason,created_at,updated_at,ended_at
                        from v2_game_save where id=? and user_id=? for update
                """, (row, index) -> save(row), saveId, userId).stream().findFirst().orElse(null);
    }

    public void archive(String saveId, String protagonistId, Instant now) {
        jdbc.update("""
                update v2_game_save set status='ARCHIVED',end_reason='PLAYER_ARCHIVED',ended_at=?,updated_at=?
                where id=? and status='ACTIVE'
                """, Timestamp.from(now), Timestamp.from(now), saveId);
        jdbc.update("update v2_character set status='ARCHIVED',updated_at=? where id=?",
                Timestamp.from(now), protagonistId);
    }

    public void endLife(String saveId, String protagonistId, String endingType, String title,
                        String summary, CharacterState state, int turnNumber,
                        long worldMinute, Instant now) {
        int saveUpdated = jdbc.update("""
                update v2_game_save set status='ENDED',end_reason=?,ended_at=?,updated_at=?
                where id=? and status='ACTIVE'
                """, endingType, Timestamp.from(now), Timestamp.from(now), saveId);
        if (saveUpdated != 1) throw new IllegalStateException("Save state changed before ending");
        jdbc.update("update v2_character set status='RETIRED',updated_at=? where id=? and save_id=?",
                Timestamp.from(now), protagonistId, saveId);
        jdbc.update("""
                update v2_character_state set life_stage_code='RETIRED',body_condition=?,
                    current_goal='此生已作收束',state_version=state_version+1,updated_at=?
                where character_id=?
                """, "LEGACY".equals(endingType) ? "已留下传承，退出纷争" : "已归隐，不再涉足旧事",
                Timestamp.from(now), protagonistId);
        jdbc.update("""
                insert into v2_life_ending(save_id,protagonist_id,ending_type,title,summary,
                    final_realm_code,final_realm_display_name,final_location_id,final_turn_number,
                    ended_world_minute,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, saveId, protagonistId, endingType, title, summary, state.realmCode(),
                state.realmDisplayName(), state.locationId(), turnNumber, worldMinute, Timestamp.from(now));
    }

    public SaveAggregate aggregate(String saveId, String userId) {
        Save save = findOwned(saveId, userId).orElse(null);
        if (save == null) return null;
        Character character = jdbc.query("""
                        select id,save_id,name,gender_expression,race,root_type,root_description,life_goal,status
                        from v2_character where id=? and save_id=?
                        """, (row, index) -> new Character(row.getString("id"), row.getString("save_id"),
                        row.getString("name"), row.getString("gender_expression"), Race.valueOf(row.getString("race")),
                        row.getString("root_type"), row.getString("root_description"), row.getString("life_goal"),
                        row.getString("status")), save.protagonistId(), saveId).stream().findFirst().orElseThrow();
        CharacterState state = jdbc.query("""
                        select s.character_id,s.location_id,l.name as location_name,s.life_stage_code,
                               s.realm_code,s.realm_display_name,s.realm_phase,s.public_identity,
                               s.body_condition,s.current_goal,s.state_version
                        from v2_character_state s join v2_location_definition l on l.id=s.location_id
                        where s.character_id=?
                        """, (row, index) -> new CharacterState(row.getString("character_id"),
                        row.getString("location_id"), row.getString("location_name"), row.getString("life_stage_code"),
                        row.getString("realm_code"), row.getString("realm_display_name"), row.getString("realm_phase"),
                        row.getString("public_identity"), row.getString("body_condition"), row.getString("current_goal"),
                        row.getLong("state_version")), character.id()).stream().findFirst().orElseThrow();
        CharacterOrigin origin = jdbc.query("""
                        select fate_title,public_identity,opening_situation,unpaid_debt
                        from v2_character_origin where character_id=? and save_id=?
                        """, (row, index) -> new CharacterOrigin(row.getString("fate_title"),
                        row.getString("public_identity"), row.getString("opening_situation"),
                        row.getString("unpaid_debt")), character.id(), saveId)
                .stream().findFirst().orElse(new CharacterOrigin("未定之命", state.publicIdentity(),
                        "此身刚刚进入青岐州，前路尚未留下定论。", "尚无已经确认的旧债"));
        Long worldMinute = jdbc.queryForObject(
                "select current_world_minute from v2_world_clock where save_id=?", Long.class, saveId);
        List<WalletBalance> wallet = jdbc.query("""
                        select w.currency_code,c.name,w.balance from v2_wallet w
                        join v2_currency_definition c on c.currency_code=w.currency_code
                        where w.save_id=? and w.owner_character_id=? order by c.currency_group,w.currency_code
                        """, (row, index) -> new WalletBalance(row.getString(1), row.getString(2), row.getBigDecimal(3)),
                saveId, character.id());
        List<InventoryItem> inventory = jdbc.query("""
                        select i.id,d.id,d.item_code,coalesce(i.custom_name,d.name),i.quantity,i.item_status,
                               i.condition_summary,i.provenance_summary
                        from v2_item_instance i join v2_item_definition d on d.id=i.item_definition_id
                        join v2_inventory inv on inv.id=i.inventory_id
                        where i.save_id=? and inv.owner_character_id=? and i.item_status<>'REMOVED'
                        order by i.acquired_world_minute,i.id
                        """, (row, index) -> new InventoryItem(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getBigDecimal(5), row.getString(6), row.getString(7), row.getString(8)),
                saveId, character.id());
        List<Ability> abilities = jdbc.query("""
                        select ca.id,d.ability_code,d.name,d.ability_type,ca.source_type,ca.source_reference_id,
                               ca.mastery_stage,ca.stability,ca.ability_status,ca.learned_world_minute
                        from v2_character_ability ca join v2_ability_definition d on d.id=ca.ability_definition_id
                        where ca.save_id=? and ca.character_id=? order by ca.learned_world_minute,ca.id
                        """, (row, index) -> new Ability(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5), row.getString(6), row.getString(7),
                        row.getString(8), row.getString(9), row.getLong(10)),
                saveId, character.id());
        CultivationProgress cultivation = jdbc.query("""
                        select path_code,path_name,current_realm_code,current_realm_phase,target_realm_code,
                               project_stage,insight_summary,state_version
                        from v2_cultivation where save_id=? and character_id=?
                        """, (row, index) -> new CultivationProgress(row.getString("path_code"),
                        row.getString("path_name"), row.getString("current_realm_code"),
                        row.getString("current_realm_phase"), row.getString("target_realm_code"),
                        row.getString("project_stage"), row.getString("insight_summary"),
                        row.getLong("state_version")), saveId, character.id())
                .stream().findFirst().orElseThrow();
        List<WorldEvent> worldEvents = jdbc.query("""
                        select e.id,e.title,e.event_scale,e.stage,e.location_id,l.name as location_name,
                               e.public_summary
                        from v2_world_event e left join v2_location_definition l on l.id=e.location_id
                        where e.save_id=? order by e.started_world_minute,e.id
                        """, (row, index) -> new WorldEvent(row.getString("id"), row.getString("title"),
                        row.getString("event_scale"), row.getString("stage"), row.getString("location_id"),
                        row.getString("location_name"), row.getString("public_summary")), saveId);
        List<KnownCharacter> knownCharacters = jdbc.query("""
                        select c.id,c.name,c.race,c.status,s.public_identity,s.location_id,
                               l.name as location_name,s.realm_code,s.realm_display_name,s.current_goal
                        from v2_character c
                        join v2_character_state s on s.character_id=c.id
                        join v2_location_definition l on l.id=s.location_id
                        where c.save_id=? and c.character_kind='NPC'
                        order by c.created_world_minute,c.id
                        """, (row, index) -> new KnownCharacter(row.getString("id"), row.getString("name"),
                        Race.valueOf(row.getString("race")), row.getString("status"),
                        row.getString("public_identity"), row.getString("location_id"),
                        row.getString("location_name"), row.getString("realm_code"),
                        row.getString("realm_display_name"), row.getString("current_goal")), saveId);
        List<Relationship> relationships = jdbc.query("""
                        select r.id,r.target_character_id,c.name as target_name,r.relationship_type,
                               r.trust_level,r.closeness_level,r.fear_level,r.interest_level,
                               r.debt_summary,r.conflict_summary,r.shared_secret_summary,
                               r.last_changed_world_minute
                        from v2_relationship r
                        join v2_character c on c.id=r.target_character_id
                        where r.save_id=? and r.source_character_id=?
                        order by r.last_changed_world_minute desc,r.id
                        """, (row, index) -> new Relationship(row.getString("id"),
                        row.getString("target_character_id"), row.getString("target_name"),
                        row.getString("relationship_type"), row.getInt("trust_level"),
                        row.getInt("closeness_level"), row.getInt("fear_level"),
                        row.getInt("interest_level"), row.getString("debt_summary"),
                        row.getString("conflict_summary"), row.getString("shared_secret_summary"),
                        row.getLong("last_changed_world_minute")), saveId, character.id());
        LifeEnding ending = jdbc.query("""
                        select ending_type,title,summary,final_realm_code,final_realm_display_name,
                               final_location_id,final_turn_number,ended_world_minute,created_at
                        from v2_life_ending where save_id=?
                        """, (row, index) -> new LifeEnding(row.getString("ending_type"),
                        row.getString("title"), row.getString("summary"), row.getString("final_realm_code"),
                        row.getString("final_realm_display_name"), row.getString("final_location_id"),
                        row.getInt("final_turn_number"), row.getLong("ended_world_minute"),
                        row.getTimestamp("created_at").toInstant()), saveId)
                .stream().findFirst().orElse(null);
        long minute = worldMinute == null ? 0 : worldMinute;
        return new SaveAggregate(save, character, state, origin, minute, WorldCalendar.fromWorldMinute(minute),
                wallet, inventory, abilities, cultivation, worldEvents, knownCharacters, relationships, ending);
    }

    public long worldMinute(String saveId) {
        Long value = jdbc.queryForObject(
                "select current_world_minute from v2_world_clock where save_id=?", Long.class, saveId);
        return value == null ? 0 : value;
    }

    public void updateWorldMinute(String saveId, long worldMinute, Instant now) {
        jdbc.update("update v2_world_clock set current_world_minute=?,updated_at=? where save_id=?",
                worldMinute, Timestamp.from(now), saveId);
    }

    public void updateCharacterLocation(String characterId, String locationId, long nextVersion, Instant now) {
        jdbc.update("""
                update v2_character_state set location_id=?,state_version=?,updated_at=? where character_id=?
                """, locationId, nextVersion, Timestamp.from(now), characterId);
    }

    public void advanceSave(String saveId, int turnNumber, long stateVersion, Instant now) {
        jdbc.update("""
                update v2_game_save set turn_number=?,state_version=?,updated_at=? where id=?
                """, turnNumber, stateVersion, Timestamp.from(now), saveId);
    }

    private Save save(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Save(row.getString("id"), row.getString("user_id"), row.getInt("slot_no"),
                row.getString("status"), Difficulty.valueOf(row.getString("difficulty")),
                row.getString("protagonist_id"), row.getString("engine_version"),
                row.getString("content_version"), row.getLong("state_version"), row.getInt("turn_number"),
                row.getString("end_reason"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getTimestamp("ended_at") == null ? null : row.getTimestamp("ended_at").toInstant());
    }
}
