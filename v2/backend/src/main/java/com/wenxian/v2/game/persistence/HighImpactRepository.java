package com.wenxian.v2.game.persistence;

import com.wenxian.v2.game.domain.Race;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class HighImpactRepository {
    private final JdbcTemplate jdbc;

    public HighImpactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CharacterAuthority character(String saveId, String characterId) {
        return jdbc.query("""
                select c.id,c.character_kind,c.name,c.race,c.status,s.location_id,
                       s.realm_code,s.realm_display_name
                from v2_character c join v2_character_state s on s.character_id=c.id
                where c.save_id=? and c.id=?
                """, (row, index) -> new CharacterAuthority(row.getString("id"),
                row.getString("character_kind"), row.getString("name"),
                Race.valueOf(row.getString("race")), row.getString("status"),
                row.getString("location_id"), row.getString("realm_code"),
                row.getString("realm_display_name")), saveId, characterId)
                .stream().findFirst().orElse(null);
    }

    public boolean characterNameExists(String saveId, String name) {
        Integer count = jdbc.queryForObject(
                "select count(*) from v2_character where save_id=? and name=?", Integer.class, saveId, name);
        return count != null && count > 0;
    }

    public AbilityDefinition abilityDefinition(String definitionId) {
        return jdbc.query("""
                select id,name,ability_type,race_scope,description from v2_ability_definition
                where id=? and active=true
                """, (row, index) -> new AbilityDefinition(row.getString("id"), row.getString("name"),
                row.getString("ability_type"), row.getString("race_scope"), row.getString("description")),
                definitionId).stream().findFirst().orElse(null);
    }

    public boolean alreadyLearned(String saveId, String characterId, String definitionId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from v2_character_ability
                where save_id=? and character_id=? and ability_definition_id=?
                """, Integer.class, saveId, characterId, definitionId);
        return count != null && count > 0;
    }

    public String insertNpc(String saveId, String name, String genderExpression, Race race,
                            String rootType, String description, String goal, String publicIdentity,
                            String locationId, String realmCode, String realmDisplayName,
                            long worldMinute, Instant now) {
        String characterId = "v2-npc-" + UUID.randomUUID();
        jdbc.update("""
                insert into v2_character(id,save_id,character_kind,name,gender_expression,race,root_type,
                                         root_description,life_goal,status,birth_world_minute,
                                         created_world_minute,created_at,updated_at)
                values (?,?,'NPC',?,?,?,?,?,?,'ALIVE',null,?,?,?)
                """, characterId, saveId, name, genderExpression, race.name(), rootType, description, goal,
                worldMinute, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                insert into v2_character_state(character_id,location_id,life_stage_code,realm_code,
                                               realm_display_name,realm_phase,public_identity,body_condition,
                                               current_goal,state_version,updated_at)
                values (?,?,'ADULT',?,?,'INITIAL',?,'状态尚稳',?,0,?)
                """, characterId, locationId, realmCode, realmDisplayName, publicIdentity, goal,
                Timestamp.from(now));
        scheduleNpcGoal(saveId, characterId, "NPC_GOAL_STARTED",
                worldMinute + 30L * 1_440L, goal, now);
        return characterId;
    }

    private void scheduleNpcGoal(String saveId, String characterId, String scheduleType,
                                 long dueWorldMinute, String goal, Instant now) {
        jdbc.update("""
                insert into v2_schedule(id,save_id,character_id,event_id,schedule_type,due_world_minute,
                                        status,payload,resolved_world_minute,created_at,updated_at)
                values (?,?,?,null,?,?,'PENDING',?,null,?,?)
                """, "v2-schedule-" + UUID.randomUUID(), saveId, characterId, scheduleType,
                dueWorldMinute, goal, Timestamp.from(now), Timestamp.from(now));
    }

    public RelationshipState lockRelationship(String saveId, String sourceCharacterId,
                                              String targetCharacterId, String relationshipType) {
        return jdbc.query("""
                select id,trust_level,closeness_level,fear_level,interest_level,state_version
                from v2_relationship
                where save_id=? and source_character_id=? and target_character_id=? and relationship_type=?
                for update
                """, (row, index) -> new RelationshipState(row.getString("id"), row.getInt("trust_level"),
                row.getInt("closeness_level"), row.getInt("fear_level"), row.getInt("interest_level"),
                row.getLong("state_version")), saveId, sourceCharacterId, targetCharacterId, relationshipType)
                .stream().findFirst().orElse(null);
    }

    public RelationshipMutation changeRelationship(String saveId, String sourceCharacterId,
                                                    String targetCharacterId, String relationshipType,
                                                    int trustDelta, int closenessDelta, int fearDelta,
                                                    int interestDelta, String summary,
                                                    long worldMinute, Instant now) {
        RelationshipState before = lockRelationship(saveId, sourceCharacterId, targetCharacterId, relationshipType);
        int beforeTrust = before == null ? 0 : before.trust();
        int beforeCloseness = before == null ? 0 : before.closeness();
        int beforeFear = before == null ? 0 : before.fear();
        int beforeInterest = before == null ? 0 : before.interest();
        int trust = clamp(beforeTrust + trustDelta);
        int closeness = clamp(beforeCloseness + closenessDelta);
        int fear = clamp(beforeFear + fearDelta);
        int interest = clamp(beforeInterest + interestDelta);
        String id;
        if (before == null) {
            id = "v2-relationship-" + UUID.randomUUID();
            jdbc.update("""
                    insert into v2_relationship(id,save_id,source_character_id,target_character_id,
                                                relationship_type,trust_level,closeness_level,fear_level,
                                                interest_level,debt_summary,conflict_summary,shared_secret_summary,
                                                last_changed_world_minute,state_version,updated_at)
                    values (?,?,?,?,?,?,?,?,?,null,?,null,?,0,?)
                    """, id, saveId, sourceCharacterId, targetCharacterId, relationshipType,
                    trust, closeness, fear, interest, summary, worldMinute, Timestamp.from(now));
        } else {
            id = before.id();
            jdbc.update("""
                    update v2_relationship set trust_level=?,closeness_level=?,fear_level=?,interest_level=?,
                        conflict_summary=?,last_changed_world_minute=?,state_version=state_version+1,updated_at=?
                    where id=?
                    """, trust, closeness, fear, interest, summary, worldMinute, Timestamp.from(now), id);
        }
        return new RelationshipMutation(id, beforeTrust, beforeCloseness, beforeFear, beforeInterest,
                trust, closeness, fear, interest);
    }

    public String learnAbility(String saveId, String characterId, String definitionId,
                               String sourceType, String sourceReferenceId,
                               long worldMinute, Instant now) {
        String id = "v2-character-ability-" + UUID.randomUUID();
        jdbc.update("""
                insert into v2_character_ability(id,save_id,character_id,ability_definition_id,
                                                 source_type,source_reference_id,mastery_stage,stability,
                                                 ability_status,learned_world_minute,last_used_world_minute,
                                                 state_version,updated_at)
                values (?,?,?,?,?,?,'初识','尚在摸索','ACTIVE',?,null,0,?)
                """, id, saveId, characterId, definitionId, sourceType, sourceReferenceId,
                worldMinute, Timestamp.from(now));
        return id;
    }

    public void breakthrough(String saveId, String characterId, String realmCode,
                             String realmDisplayName, String reason, long stateVersion, Instant now) {
        jdbc.update("""
                update v2_character_state set realm_code=?,realm_display_name=?,realm_phase='INITIAL',
                    state_version=?,updated_at=? where character_id=?
                """, realmCode, realmDisplayName, stateVersion, Timestamp.from(now), characterId);
        jdbc.update("""
                update v2_cultivation set current_realm_code=?,current_realm_phase='INITIAL',
                    target_realm_code=null,project_stage='DORMANT',insight_summary=?,
                    state_version=state_version+1,updated_at=? where save_id=? and character_id=?
                """, realmCode, reason, Timestamp.from(now), saveId, characterId);
    }

    public void endProtagonist(String saveId, String characterId, String cause,
                               long stateVersion, Instant now) {
        jdbc.update("update v2_character set status='DEAD',updated_at=? where id=? and save_id=?",
                Timestamp.from(now), characterId, saveId);
        jdbc.update("""
                update v2_character_state set body_condition=?,state_version=?,updated_at=? where character_id=?
                """, "已故：" + cause, stateVersion, Timestamp.from(now), characterId);
        jdbc.update("""
                update v2_game_save set status='ENDED',end_reason='DEATH',ended_at=?,updated_at=? where id=?
                """, Timestamp.from(now), Timestamp.from(now), saveId);
    }

    private int clamp(int value) {
        return Math.max(-10, Math.min(10, value));
    }

    public record CharacterAuthority(String id, String kind, String name, Race race, String status,
                                     String locationId, String realmCode, String realmDisplayName) {
    }

    public record AbilityDefinition(String id, String name, String type, String raceScope, String description) {
    }

    public record RelationshipState(String id, int trust, int closeness, int fear, int interest,
                                    long stateVersion) {
    }

    public record RelationshipMutation(String id, int beforeTrust, int beforeCloseness, int beforeFear,
                                       int beforeInterest, int afterTrust, int afterCloseness, int afterFear,
                                       int afterInterest) {
    }
}
