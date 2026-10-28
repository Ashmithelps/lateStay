package com.latestay.stay;

import com.latestay.hotel.AvailabilityRepository;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.stay.SearchResponse.RoomOption;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HotelDetailService {

    private final HotelRepository hotels;
    private final StayRequestRepository stayRequests;
    private final AvailabilityRepository availability;
    private final SearchService search;
    private final JdbcClient jdbc;

    public HotelDetailService(HotelRepository hotels, StayRequestRepository stayRequests,
            AvailabilityRepository availability, SearchService search, JdbcClient jdbc) {
        this.hotels = hotels;
        this.stayRequests = stayRequests;
        this.availability = availability;
        this.search = search;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public HotelDetail detail(String slug, UUID stayRequestId) {
        Hotel hotel = hotels.findBySlug(slug).filter(Hotel::active).orElseThrow(() -> new NotFoundException("hotel", slug));
        HotelDetail.Stay stay = null;
        if (stayRequestId != null) {
            StayRequest request = stayRequests.find(stayRequestId)
                    .orElseThrow(() -> new NotFoundException("stay request", stayRequestId));
            StayWindow window = request.window();
            List<RoomOption> rooms = search.roomOptions(hotel.id(), window, request.guests());
            double metres = jdbc.sql("""
                            SELECT ST_Distance(h.location, s.location) FROM hotel h, stay_request s
                            WHERE h.id = :hotel AND s.id = :stay""")
                    .param("hotel", hotel.id()).param("stay", stayRequestId).query(Double.class).single();
            stay = new HotelDetail.Stay(stayRequestId, window.nightOf(), window.slotType(), window.arrive(),
                    window.leave(), window.slotType().arriveFrom(), window.slotType().arriveUntil(),
                    window.slotType().leaveBy(), request.guests(), Math.round(metres / 10.0) / 100.0,
                    availability.roomsFree(hotel.id(), window.nightOf()),
                    rooms.isEmpty() ? null : rooms.getFirst().fromPrice(), rooms);
        }
        return new HotelDetail(hotel.id(), hotel.slug(), hotel.name(), hotel.town(), hotel.address(), hotel.lat(),
                hotel.lng(), hotel.photos(), hotel.amenities(),
                new HotelDetail.Policies(hotel.policies().coupleFriendly(), hotel.policies().localIdOk(),
                        hotel.policies().checkInNotes()),
                hotel.rating(), stay);
    }
}
