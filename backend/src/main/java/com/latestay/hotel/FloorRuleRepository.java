package com.latestay.hotel;

import com.latestay.negotiation.SlotType;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FloorRuleRepository {

    private final JdbcClient jdbc;

    public FloorRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FloorRule> forRoomType(UUID roomTypeId) {
        return jdbc.sql("SELECT * FROM floor_rule WHERE room_type_id = :id ORDER BY slot_type, floor_price")
                .param("id", roomTypeId).query(FloorRuleRepository::map).list();
    }

    public List<FloorRule> forRoomTypes(List<UUID> roomTypeIds, SlotType slot) {
        if (roomTypeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM floor_rule WHERE room_type_id IN (:ids) AND slot_type = :slot")
                .param("ids", roomTypeIds).param("slot", slot.name()).query(FloorRuleRepository::map).list();
    }

    public Optional<FloorRule> find(UUID id) {
        return jdbc.sql("SELECT * FROM floor_rule WHERE id = :id").param("id", id).query(FloorRuleRepository::map)
                .optional();
    }

    public FloorRule insert(FloorRule rule) {
        UUID id = jdbc.sql("""
                        INSERT INTO floor_rule (room_type_id, slot_type, weekdays, season_start, season_end,
                                                floor_price, min_hours)
                        VALUES (:roomType, :slot, :weekdays, :start, :end, :floor, :minHours) RETURNING id""")
                .params(params(rule))
                .query(UUID.class).single();
        return new FloorRule(id, rule.roomTypeId(), rule.slotType(), rule.weekdays(), rule.seasonStart(),
                rule.seasonEnd(), rule.floorPrice(), rule.minHours());
    }

    public boolean update(UUID id, FloorRule rule) {
        var params = params(rule);
        params.put("id", id);
        return jdbc.sql("""
                        UPDATE floor_rule SET slot_type = :slot, weekdays = :weekdays, season_start = :start,
                               season_end = :end, floor_price = :floor, min_hours = :minHours
                        WHERE id = :id AND room_type_id = :roomType""")
                .params(params).update() > 0;
    }

    public boolean delete(UUID roomTypeId, UUID id) {
        return jdbc.sql("DELETE FROM floor_rule WHERE id = :id AND room_type_id = :roomType")
                .param("id", id).param("roomType", roomTypeId).update() > 0;
    }

    private static java.util.Map<String, Object> params(FloorRule rule) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("roomType", rule.roomTypeId());
        params.put("slot", rule.slotType().name());
        params.put("weekdays", rule.weekdays());
        params.put("start", rule.seasonStart());
        params.put("end", rule.seasonEnd());
        params.put("floor", rule.floorPrice());
        params.put("minHours", rule.minHours());
        return params;
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private static FloorRule map(ResultSet rs, int row) throws SQLException {
        return new FloorRule(rs.getObject("id", UUID.class), rs.getObject("room_type_id", UUID.class),
                SlotType.valueOf(rs.getString("slot_type")), rs.getInt("weekdays"), date(rs, "season_start"),
                date(rs, "season_end"), rs.getLong("floor_price"), rs.getInt("min_hours"));
    }
}
