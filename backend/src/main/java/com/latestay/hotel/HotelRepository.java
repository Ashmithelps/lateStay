package com.latestay.hotel;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HotelRepository {

    private static final String SELECT = """
            SELECT id, name, slug, town, address, ST_Y(location::geometry) AS lat, ST_X(location::geometry) AS lng,
                   whatsapp_number, amenities, policies::text AS policies, rating, active
            FROM hotel""";

    private final JdbcClient jdbc;

    public HotelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Hotel> findAll() {
        List<Hotel> hotels = jdbc.sql(SELECT + " ORDER BY town, name").query(HotelRepository::map).list();
        Map<UUID, List<String>> photos = photos(hotels.stream().map(Hotel::id).toList());
        return hotels.stream().map(h -> withPhotos(h, photos)).toList();
    }

    public Optional<Hotel> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(HotelRepository::map).optional()
                .map(h -> withPhotos(h, photos(List.of(id))));
    }

    public Optional<Hotel> findBySlug(String slug) {
        return jdbc.sql(SELECT + " WHERE slug = :slug").param("slug", slug).query(HotelRepository::map).optional()
                .map(h -> withPhotos(h, photos(List.of(h.id()))));
    }

    public Hotel insert(Hotel hotel) {
        UUID id = jdbc.sql("""
                        INSERT INTO hotel (name, slug, town, address, location, whatsapp_number, amenities, policies,
                                           rating, active)
                        VALUES (:name, :slug, :town, :address, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                :whatsapp, :amenities, CAST(:policies AS jsonb), :rating, :active)
                        RETURNING id""")
                .params(writeParams(hotel))
                .query(UUID.class).single();
        replacePhotos(id, hotel.photos());
        return hotel.withId(id);
    }

    /** @return false if no hotel has this id */
    public boolean update(UUID id, Hotel hotel) {
        Map<String, Object> params = writeParams(hotel);
        params.put("id", id);
        int updated = jdbc.sql("""
                        UPDATE hotel SET name = :name, slug = :slug, town = :town, address = :address,
                               location = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                               whatsapp_number = :whatsapp, amenities = :amenities, policies = CAST(:policies AS jsonb),
                               rating = :rating, active = :active, updated_at = now()
                        WHERE id = :id""")
                .params(params).update();
        if (updated == 0) {
            return false;
        }
        replacePhotos(id, hotel.photos());
        return true;
    }

    /** Soft delete: the hotel disappears from search but its sessions keep their history. */
    public boolean deactivate(UUID id) {
        return jdbc.sql("UPDATE hotel SET active = false, updated_at = now() WHERE id = :id").param("id", id).update() > 0;
    }

    private void replacePhotos(UUID hotelId, List<String> urls) {
        jdbc.sql("DELETE FROM hotel_photo WHERE hotel_id = :id").param("id", hotelId).update();
        for (int i = 0; i < urls.size(); i++) {
            jdbc.sql("INSERT INTO hotel_photo (hotel_id, url, sort_order) VALUES (:id, :url, :order)")
                    .param("id", hotelId).param("url", urls.get(i)).param("order", i).update();
        }
    }

    public Map<UUID, List<String>> photos(List<UUID> hotelIds) {
        if (hotelIds.isEmpty()) {
            return Map.of();
        }
        record Photo(UUID hotelId, String url) {
        }
        return jdbc.sql("SELECT hotel_id, url FROM hotel_photo WHERE hotel_id IN (:ids) ORDER BY hotel_id, sort_order")
                .param("ids", hotelIds)
                .query((rs, n) -> new Photo(rs.getObject("hotel_id", UUID.class), rs.getString("url")))
                .list().stream()
                .collect(Collectors.groupingBy(Photo::hotelId, Collectors.mapping(Photo::url, Collectors.toList())));
    }

    private static Map<String, Object> writeParams(Hotel hotel) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("name", hotel.name());
        params.put("slug", hotel.slug());
        params.put("town", hotel.town());
        params.put("address", hotel.address());
        params.put("lat", hotel.lat());
        params.put("lng", hotel.lng());
        params.put("whatsapp", hotel.whatsappNumber());
        params.put("amenities", hotel.amenities().toArray(String[]::new));
        params.put("policies", Json.write(hotel.policies().toJson()));
        params.put("rating", hotel.rating());
        params.put("active", hotel.active());
        return params;
    }

    private static Hotel withPhotos(Hotel hotel, Map<UUID, List<String>> photos) {
        return new Hotel(hotel.id(), hotel.name(), hotel.slug(), hotel.town(), hotel.address(), hotel.lat(),
                hotel.lng(), hotel.whatsappNumber(), hotel.amenities(), hotel.policies(), hotel.rating(),
                hotel.active(), photos.getOrDefault(hotel.id(), List.of()));
    }

    static List<String> textArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Hotel map(ResultSet rs, int row) throws SQLException {
        return new Hotel(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("slug"),
                rs.getString("town"),
                rs.getString("address"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getString("whatsapp_number"),
                textArray(rs, "amenities"),
                HotelPolicies.fromJson(Json.readMap(rs.getString("policies"))),
                rs.getBigDecimal("rating"),
                rs.getBoolean("active"),
                List.of());
    }
}
