package com.wenxian.v2.game.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class LocationRepository {
    private final JdbcTemplate jdbc;

    public LocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String locationId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from v2_location_definition where id=? and active=true", Integer.class, locationId);
        return count != null && count > 0;
    }

    public TravelEdge directEdge(String currentLocationId, String targetLocationId) {
        return jdbc.query("""
                        select id,from_location_id,to_location_id,travel_mode,travel_minutes,bidirectional
                        from v2_location_edge
                        where active=true and (
                          (from_location_id=? and to_location_id=?) or
                          (bidirectional=true and from_location_id=? and to_location_id=?))
                        order by travel_minutes limit 1
                        """, (row, index) -> new TravelEdge(row.getString("id"), currentLocationId,
                        targetLocationId, row.getString("travel_mode"), row.getLong("travel_minutes")),
                currentLocationId, targetLocationId, targetLocationId, currentLocationId)
                .stream().findFirst().orElse(null);
    }

    public List<KnownLocation> knownLocations(String saveId) {
        return jdbc.query("""
                        select d.id,d.location_code,d.name,d.location_type,d.parent_location_id,
                               s.known,s.accessible,s.local_state
                        from v2_save_location_state s
                        join v2_location_definition d on d.id=s.location_id
                        where s.save_id=? and s.known=true order by d.location_type,d.name
                        """, (row, index) -> new KnownLocation(row.getString("id"), row.getString("location_code"),
                        row.getString("name"), row.getString("location_type"), row.getString("parent_location_id"),
                        row.getBoolean("known"), row.getBoolean("accessible"), row.getString("local_state")), saveId);
    }

    public record TravelEdge(String id, String fromLocationId, String toLocationId,
                             String travelMode, long travelMinutes) {
    }

    public record KnownLocation(String id, String code, String name, String type, String parentId,
                                boolean known, boolean accessible, String localState) {
    }
}
