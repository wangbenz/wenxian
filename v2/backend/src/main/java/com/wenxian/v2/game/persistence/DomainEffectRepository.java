package com.wenxian.v2.game.persistence;

import com.wenxian.v2.game.domain.Race;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class DomainEffectRepository {
    private final JdbcTemplate jdbc;

    public DomainEffectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ItemDefinition> activeItemDefinitions() {
        return jdbc.query("""
                select id,name,item_type,stackable,consumable,tradeable,description
                from v2_item_definition where active=true order by id
                """, (row, index) -> new ItemDefinition(row.getString("id"), row.getString("name"),
                row.getString("item_type"), row.getBoolean("stackable"), row.getBoolean("consumable"),
                row.getBoolean("tradeable"), row.getString("description")));
    }

    public List<AbilityDefinition> activeAbilityDefinitions(Race race) {
        return jdbc.query("""
                select id,name,ability_type,race_scope,description
                from v2_ability_definition
                where active=true and race_scope in ('ANY',?)
                order by case when race_scope=? then 0 else 1 end,id
                """, (row, index) -> new AbilityDefinition(row.getString("id"), row.getString("name"),
                row.getString("ability_type"), row.getString("race_scope"), row.getString("description")),
                race.name(), race.name());
    }

    public ItemDefinition itemDefinition(String definitionId) {
        return jdbc.query("""
                select id,name,item_type,stackable,consumable,tradeable,description
                from v2_item_definition where id=? and active=true
                """, (row, index) -> new ItemDefinition(row.getString("id"), row.getString("name"),
                row.getString("item_type"), row.getBoolean("stackable"), row.getBoolean("consumable"),
                row.getBoolean("tradeable"), row.getString("description")), definitionId)
                .stream().findFirst().orElse(null);
    }

    public PriceAnchor priceAnchor(String locationId, String definitionId, String currencyCode) {
        return jdbc.query("""
                with recursive ancestry(id,parent_location_id,depth) as (
                    select id,parent_location_id,0 from v2_location_definition where id=?
                    union all
                    select parent.id,parent.parent_location_id,ancestry.depth+1
                    from v2_location_definition parent join ancestry on parent.id=ancestry.parent_location_id
                )
                select price.minimum_amount,price.typical_amount,price.maximum_amount
                from v2_price_anchor price join ancestry on ancestry.id=price.location_id
                where price.item_definition_id=? and price.currency_code=?
                order by ancestry.depth limit 1
                """, (row, index) -> new PriceAnchor(row.getBigDecimal("minimum_amount"),
                row.getBigDecimal("typical_amount"), row.getBigDecimal("maximum_amount")),
                locationId, definitionId, currencyCode).stream().findFirst().orElse(null);
    }

    public OwnedItem lockOwnedItem(String saveId, String characterId, String itemId) {
        return jdbc.query("""
                select i.id,i.item_definition_id,coalesce(i.custom_name,d.name) as item_name,
                       i.quantity,i.item_status,d.consumable,d.tradeable
                from v2_item_instance i
                join v2_item_definition d on d.id=i.item_definition_id
                join v2_inventory inv on inv.id=i.inventory_id
                where i.id=? and i.save_id=? and inv.owner_character_id=? for update
                """, (row, index) -> new OwnedItem(row.getString("id"), row.getString("item_definition_id"),
                row.getString("item_name"), row.getBigDecimal("quantity"), row.getString("item_status"),
                row.getBoolean("consumable"), row.getBoolean("tradeable")), itemId, saveId, characterId)
                .stream().findFirst().orElse(null);
    }

    public void removeItemQuantity(String saveId, String characterId, String itemId, BigDecimal amount,
                                   String eventType, String turnId, String reason, long worldMinute, Instant now) {
        OwnedItem item = lockOwnedItem(saveId, characterId, itemId);
        if (item == null || !"AVAILABLE".equals(item.status()) || item.quantity().compareTo(amount) < 0) {
            throw new IllegalStateException("Item state changed before commit");
        }
        BigDecimal after = item.quantity().subtract(amount);
        int updated = jdbc.update("""
                update v2_item_instance set quantity=?,item_status=?,state_version=state_version+1,updated_at=?
                where id=? and save_id=?
                """, after, after.signum() == 0 ? "REMOVED" : "AVAILABLE", Timestamp.from(now), itemId, saveId);
        if (updated != 1) throw new IllegalStateException("Unable to update item");
        insertItemEvent(saveId, itemId, eventType, amount.negate(), characterId, null,
                turnId, reason, worldMinute, now);
    }

    public String acquireItem(String saveId, String characterId, ItemDefinition definition, BigDecimal amount,
                              String turnId, String reason, long worldMinute, Instant now) {
        String inventoryId = jdbc.queryForObject("""
                select id from v2_inventory where save_id=? and owner_character_id=? and inventory_type='PERSONAL'
                """, String.class, saveId, characterId);
        String itemId = null;
        if (definition.stackable()) {
            var existing = jdbc.query("""
                    select i.id from v2_item_instance i
                    where i.save_id=? and i.inventory_id=? and i.item_definition_id=? and i.item_status='AVAILABLE'
                    order by i.acquired_world_minute,i.id limit 1 for update
                    """, (row, index) -> row.getString(1), saveId, inventoryId, definition.id());
            if (!existing.isEmpty()) {
                itemId = existing.getFirst();
                jdbc.update("""
                        update v2_item_instance set quantity=quantity+?,state_version=state_version+1,updated_at=?
                        where id=? and save_id=?
                        """, amount, Timestamp.from(now), itemId, saveId);
            }
        }
        if (itemId == null) {
            itemId = "v2-item-" + UUID.randomUUID();
            jdbc.update("""
                    insert into v2_item_instance(id,save_id,inventory_id,item_definition_id,custom_name,quantity,
                                                 item_status,condition_summary,provenance_summary,
                                                 acquired_world_minute,state_version,updated_at)
                    values (?,?,?,?,null,?,'AVAILABLE','完好',?,?,0,?)
                    """, itemId, saveId, inventoryId, definition.id(), amount, reason, worldMinute,
                    Timestamp.from(now));
        }
        insertItemEvent(saveId, itemId, "ACQUIRED", amount, null, characterId,
                turnId, reason, worldMinute, now);
        return itemId;
    }

    private void insertItemEvent(String saveId, String itemId, String eventType, BigDecimal change,
                                 String fromOwnerId, String toOwnerId, String turnId, String reason,
                                 long worldMinute, Instant now) {
        jdbc.update("""
                insert into v2_item_event(id,save_id,item_instance_id,event_type,quantity_change,
                                          from_owner_id,to_owner_id,source_turn_id,reason,world_minute,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, "v2-item-event-" + UUID.randomUUID(), saveId, itemId, eventType, change,
                fromOwnerId, toOwnerId, turnId, reason, worldMinute, Timestamp.from(now));
    }

    public Wallet lockWallet(String saveId, String characterId, String currencyCode) {
        return jdbc.query("""
                select id,currency_code,balance from v2_wallet
                where save_id=? and owner_character_id=? and currency_code=? for update
                """, (row, index) -> new Wallet(row.getString("id"), row.getString("currency_code"),
                row.getBigDecimal("balance")), saveId, characterId, currencyCode)
                .stream().findFirst().orElse(null);
    }

    public WalletMutation mutateWallet(String saveId, String characterId, String currencyCode,
                                       BigDecimal signedAmount, String turnId, String reason,
                                       long worldMinute, Instant now) {
        Wallet wallet = lockWallet(saveId, characterId, currencyCode);
        if (wallet == null) throw new IllegalStateException("Wallet not found");
        BigDecimal after = wallet.balance().add(signedAmount);
        if (after.signum() < 0) throw new IllegalStateException("Wallet balance changed before commit");
        jdbc.update("""
                update v2_wallet set balance=?,state_version=state_version+1,updated_at=? where id=?
                """, after, Timestamp.from(now), wallet.id());
        jdbc.update("""
                insert into v2_wallet_ledger(id,wallet_id,save_id,source_turn_id,transaction_type,amount,
                                             balance_before,balance_after,counterparty_character_id,reason,
                                             world_minute,created_at)
                values (?,?,?,?,?,?,?,?,null,?,?,?)
                """, "v2-wallet-ledger-" + UUID.randomUUID(), wallet.id(), saveId, turnId,
                signedAmount.signum() >= 0 ? "CREDIT" : "DEBIT", signedAmount.abs(), wallet.balance(), after,
                reason, worldMinute, Timestamp.from(now));
        return new WalletMutation(wallet.id(), wallet.balance(), after);
    }

    public void markAbilityUsed(String saveId, String characterId, String abilityId,
                                long worldMinute, Instant now) {
        int updated = jdbc.update("""
                update v2_character_ability set last_used_world_minute=?,state_version=state_version+1,updated_at=?
                where id=? and save_id=? and character_id=? and ability_status='ACTIVE'
                """, worldMinute, Timestamp.from(now), abilityId, saveId, characterId);
        if (updated != 1) throw new IllegalStateException("Ability state changed before commit");
    }

    public String bodyCondition(String characterId) {
        return jdbc.queryForObject("select body_condition from v2_character_state where character_id=?",
                String.class, characterId);
    }

    public void setBodyCondition(String characterId, String value, long stateVersion, Instant now) {
        int updated = jdbc.update("""
                update v2_character_state set body_condition=?,state_version=?,updated_at=? where character_id=?
                """, value, stateVersion, Timestamp.from(now), characterId);
        if (updated != 1) throw new IllegalStateException("Character state changed before commit");
    }

    public CultivationState cultivation(String saveId, String characterId) {
        return jdbc.query("""
                select project_stage,insight_summary from v2_cultivation where save_id=? and character_id=?
                """, (row, index) -> new CultivationState(row.getString("project_stage"),
                row.getString("insight_summary")), saveId, characterId).stream().findFirst().orElse(null);
    }

    public void setCultivationInsight(String saveId, String characterId, String value, Instant now) {
        int updated = jdbc.update("""
                update v2_cultivation set project_stage='PRACTICING',insight_summary=?,
                    state_version=state_version+1,updated_at=? where save_id=? and character_id=?
                """, value, Timestamp.from(now), saveId, characterId);
        if (updated != 1) throw new IllegalStateException("Cultivation state changed before commit");
    }

    public EventState lockEvent(String saveId, String eventId) {
        return jdbc.query("""
                select stage,public_summary from v2_world_event where save_id=? and id=? for update
                """, (row, index) -> new EventState(row.getString("stage"), row.getString("public_summary")),
                saveId, eventId).stream().findFirst().orElse(null);
    }

    public void advanceEvent(String saveId, String eventId, String stage, String summary,
                             long worldMinute, Instant now) {
        Long nextTransition = nextEventTransition(stage, worldMinute);
        int updated = jdbc.update("""
                update v2_world_event set stage=?,public_summary=?,
                    ended_world_minute=case when ?='AFTERMATH' then ? else ended_world_minute end,
                    next_transition_world_minute=?,state_version=state_version+1,updated_at=?
                where save_id=? and id=?
                """, stage, summary, stage, worldMinute, nextTransition, Timestamp.from(now), saveId, eventId);
        if (updated != 1) throw new IllegalStateException("Event state changed before commit");
    }

    public List<DueEvent> dueEvents(String saveId, long worldMinute) {
        return jdbc.query("""
                select id,template_code,title,stage,next_transition_world_minute,public_summary
                from v2_world_event
                where save_id=? and stage<>'AFTERMATH' and next_transition_world_minute is not null
                  and next_transition_world_minute<=?
                order by next_transition_world_minute,id for update
                """, (row, index) -> new DueEvent(row.getString("id"), row.getString("template_code"),
                row.getString("title"), row.getString("stage"),
                row.getLong("next_transition_world_minute"), row.getString("public_summary")),
                saveId, worldMinute);
    }

    public void advanceDueEvent(String saveId, DueEvent event, String nextStage, String summary,
                                long transitionMinute, Instant now) {
        Long nextTransition = nextEventTransition(nextStage, transitionMinute);
        int updated = jdbc.update("""
                update v2_world_event set stage=?,public_summary=?,next_transition_world_minute=?,
                    ended_world_minute=case when ?='AFTERMATH' then ? else ended_world_minute end,
                    state_version=state_version+1,updated_at=?
                where save_id=? and id=? and stage=? and next_transition_world_minute=?
                """, nextStage, summary, nextTransition, nextStage, transitionMinute, Timestamp.from(now),
                saveId, event.id(), event.stage(), event.nextTransitionWorldMinute());
        if (updated != 1) throw new IllegalStateException("Scheduled event state changed before commit");
    }

    public List<DueSchedule> dueSchedules(String saveId, long worldMinute) {
        return jdbc.query("""
                select s.id,s.character_id,s.schedule_type,s.due_world_minute,s.payload,
                       c.name,c.status,cs.current_goal
                from v2_schedule s
                join v2_character c on c.id=s.character_id
                join v2_character_state cs on cs.character_id=c.id
                where s.save_id=? and s.status='PENDING' and s.due_world_minute<=?
                  and c.status='ALIVE'
                order by s.due_world_minute,s.id for update
                """, (row, index) -> new DueSchedule(row.getString("id"), row.getString("character_id"),
                row.getString("schedule_type"), row.getLong("due_world_minute"), row.getString("payload"),
                row.getString("name"), row.getString("current_goal")), saveId, worldMinute);
    }

    public ScheduleResolution resolveNpcSchedule(String saveId, DueSchedule schedule, Instant now) {
        ScheduleResolution resolution = nextNpcGoal(schedule);
        int updated = jdbc.update("""
                update v2_schedule set status='RESOLVED',resolved_world_minute=?,updated_at=?
                where id=? and save_id=? and status='PENDING'
                """, schedule.dueWorldMinute(), Timestamp.from(now), schedule.id(), saveId);
        if (updated != 1) throw new IllegalStateException("Scheduled character action changed before commit");
        jdbc.update("""
                update v2_character_state set current_goal=?,state_version=state_version+1,updated_at=?
                where character_id=?
                """, resolution.currentGoal(), Timestamp.from(now), schedule.characterId());
        if (resolution.nextScheduleType() != null) {
            jdbc.update("""
                    insert into v2_schedule(id,save_id,character_id,event_id,schedule_type,due_world_minute,
                                            status,payload,resolved_world_minute,created_at,updated_at)
                    values (?,?,?,null,?,?,'PENDING',?,null,?,?)
                    """, "v2-schedule-" + UUID.randomUUID(), saveId, schedule.characterId(),
                    resolution.nextScheduleType(), schedule.dueWorldMinute() + resolution.nextDelayMinutes(),
                    schedule.payload(), Timestamp.from(now), Timestamp.from(now));
        }
        return resolution;
    }

    private ScheduleResolution nextNpcGoal(DueSchedule schedule) {
        String originalGoal = schedule.payload() == null || schedule.payload().isBlank()
                ? schedule.currentGoal() : schedule.payload();
        return switch (schedule.scheduleType()) {
            case "NPC_GOAL_STARTED" -> new ScheduleResolution(
                    "正在推进：" + originalGoal, "NPC_GOAL_ADVANCED", 60L * 1_440L,
                    schedule.characterName() + "已经开始处理自己的事情：" + originalGoal);
            case "NPC_GOAL_ADVANCED" -> new ScheduleResolution(
                    "已在此事上有所进展：" + originalGoal, "NPC_GOAL_SETTLED", 120L * 1_440L,
                    schedule.characterName() + "没有停在上次相见之处，已在自己的目标上有所进展。");
            default -> new ScheduleResolution(
                    "此事已有阶段性结果，正在重新安排生活", null, 0,
                    schedule.characterName() + "已经走完这段个人计划，并开始重新安排之后的生活。");
        };
    }

    private Long nextEventTransition(String stage, long fromMinute) {
        long days = switch (stage) {
            case "SEED" -> 30;
            case "SIGN" -> 20;
            case "BUILDUP" -> 10;
            case "ERUPTION" -> 5;
            case "TURN" -> 3;
            case "RESULT" -> 10;
            default -> 0;
        };
        return days == 0 ? null : fromMinute + days * 1_440L;
    }

    public record ItemDefinition(String id, String name, String itemType, boolean stackable,
                                 boolean consumable, boolean tradeable, String description) {
    }

    public record AbilityDefinition(String id, String name, String abilityType,
                                    String raceScope, String description) {
    }

    public record OwnedItem(String id, String definitionId, String name, BigDecimal quantity,
                            String status, boolean consumable, boolean tradeable) {
    }

    public record Wallet(String id, String currencyCode, BigDecimal balance) {
    }

    public record WalletMutation(String walletId, BigDecimal before, BigDecimal after) {
    }

    public record PriceAnchor(BigDecimal minimum, BigDecimal typical, BigDecimal maximum) {
    }

    public record CultivationState(String stage, String insight) {
    }

    public record EventState(String stage, String summary) {
    }

    public record DueEvent(String id, String templateCode, String title, String stage,
                           long nextTransitionWorldMinute, String publicSummary) {
    }

    public record DueSchedule(String id, String characterId, String scheduleType,
                              long dueWorldMinute, String payload, String characterName,
                              String currentGoal) {
    }

    public record ScheduleResolution(String currentGoal, String nextScheduleType,
                                     long nextDelayMinutes, String summary) {
    }
}
