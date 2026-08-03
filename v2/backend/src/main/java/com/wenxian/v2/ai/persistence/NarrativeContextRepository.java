package com.wenxian.v2.ai.persistence;

import com.wenxian.v2.ai.domain.TurnNarrativeContext.ActiveEvent;
import com.wenxian.v2.ai.domain.TurnNarrativeContext.ConfirmedFact;
import com.wenxian.v2.ai.domain.TurnNarrativeContext.RecentTurn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class NarrativeContextRepository {
    private final JdbcTemplate jdbc;

    public NarrativeContextRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<RecentTurn> recentTurns(String saveId, int limit) {
        return jdbc.query("""
                        select turn_number,player_action,outcome_summary,narrative_text
                        from v2_turn where save_id=? order by turn_number desc limit ?
                        """, (row, index) -> new RecentTurn(row.getInt("turn_number"),
                        row.getString("player_action"), row.getString("outcome_summary"),
                        excerpt(row.getString("narrative_text"), 1200)), saveId, Math.max(1, Math.min(limit, 6)));
    }

    public List<ConfirmedFact> confirmedFacts(String saveId, int limit) {
        return jdbc.query("""
                        select c.domain_type,c.entity_id,c.after_payload,t.world_minute_after
                        from v2_domain_change c join v2_turn t on t.id=c.turn_id
                        where c.save_id=? and c.change_type='NARRATIVE_FACT_CONFIRMED'
                        order by t.turn_number desc,c.change_order desc limit ?
                        """, (row, index) -> new ConfirmedFact(row.getString("domain_type"),
                        row.getString("entity_id"), row.getString("after_payload"),
                        row.getLong("world_minute_after")), saveId, Math.max(1, Math.min(limit, 20)));
    }

    public List<ActiveEvent> activeEvents(String saveId) {
        return jdbc.query("""
                        select e.id,e.title,e.stage,l.name as location_name,e.public_summary
                        from v2_world_event e left join v2_location_definition l on l.id=e.location_id
                        where e.save_id=? and e.ended_world_minute is null
                        order by e.next_transition_world_minute,e.id limit 6
                        """, (row, index) -> new ActiveEvent(row.getString("id"), row.getString("title"),
                        row.getString("stage"), row.getString("location_name"),
                        row.getString("public_summary")), saveId);
    }

    private String excerpt(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value == null ? "" : value;
        return value.substring(0, maxLength) + "……";
    }
}
