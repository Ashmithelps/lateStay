package com.latestay.hotel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RoomTypeRepository {

    private final JdbcClient jdbc;

    public RoomTypeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<RoomType> forHotel(UUID hotelId) {
        return jdbc.sql("SELECT * FROM room_type WHERE hotel_id = :id ORDER BY list_price, name")
                .param("id", hotelId).query(RoomTypeRepository::map).list();
    }

    /** Room types of these hotels that sleep at least {@code guests}. */
    public List<RoomType> fitting(List<UUID> hotelIds, int guests) {
        if (hotelIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM room_type WHERE hotel_id IN (:ids) AND max_guests >= :guests")
                .param("ids", hotelIds).param("guests", guests).query(RoomTypeRepository::map).list();
    }

    public Optional<RoomType> find(UUID id) {
        return jdbc.sql("SELECT * FROM room_type WHERE id = :id").param("id", id).query(RoomTypeRepository::map)
                .optional();
    }

    public RoomType insert(UUID hotelId, String name, long listPrice, int maxGuests) {
        UUID id = jdbc.sql("""
                        INSERT INTO room_type (hotel_id, name, list_price, max_guests)
                        VALUES (:hotel, :name, :price, :guests) RETURNING id""")
                .param("hotel", hotelId).param("name", name).param("price", listPrice).param("guests", maxGuests)
                .query(UUID.class).single();
        return new RoomType(id, hotelId, name, listPrice, maxGuests);
    }

    public boolean update(UUID id, String name, long listPrice, int maxGuests) {
        return jdbc.sql("UPDATE room_type SET name = :name, list_price = :price, max_guests = :guests WHERE id = :id")
                .param("id", id).param("name", name).param("price", listPrice).param("guests", maxGuests)
                .update() > 0;
    }

    /** @throws ConflictException if a negotiation still references the room type */
    public boolean delete(UUID id) {
        try {
            return jdbc.sql("DELETE FROM room_type WHERE id = :id").param("id", id).update() > 0;
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("room type " + id + " has negotiations; it can't be deleted");
        }
    }

    private static RoomType map(ResultSet rs, int row) throws SQLException {
        return new RoomType(rs.getObject("id", UUID.class), rs.getObject("hotel_id", UUID.class),
                rs.getString("name"), rs.getLong("list_price"), rs.getInt("max_guests"));
    }
}
