package com.latestay.admin;

import com.latestay.admin.AdminDtos.AvailabilityBody;
import com.latestay.admin.AdminDtos.AvailabilityView;
import com.latestay.admin.AdminDtos.FloorRuleBody;
import com.latestay.admin.AdminDtos.HotelBody;
import com.latestay.admin.AdminDtos.HotelView;
import com.latestay.admin.AdminDtos.RoomTypeBody;
import com.latestay.admin.AdminDtos.RoomTypeView;
import com.latestay.hotel.AvailabilityRepository;
import com.latestay.hotel.AvailabilitySource;
import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleRepository;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.hotel.RoomType;
import com.latestay.hotel.RoomTypeRepository;
import com.latestay.stay.InvalidStayException;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Hotel, room type, floor rule and availability management. HTTP basic auth (see SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
class AdminController {

    private final HotelRepository hotels;
    private final RoomTypeRepository roomTypes;
    private final FloorRuleRepository floorRules;
    private final AvailabilityRepository availability;

    AdminController(HotelRepository hotels, RoomTypeRepository roomTypes, FloorRuleRepository floorRules,
            AvailabilityRepository availability) {
        this.hotels = hotels;
        this.roomTypes = roomTypes;
        this.floorRules = floorRules;
        this.availability = availability;
    }

    // Hotels

    @GetMapping("/hotels")
    List<HotelView> hotels() {
        return hotels.findAll().stream().map(HotelView::of).toList();
    }

    @GetMapping("/hotels/{id}")
    HotelView hotel(@PathVariable UUID id) {
        return HotelView.of(hotels.find(id).orElseThrow(() -> new NotFoundException("hotel", id)));
    }

    @PostMapping("/hotels")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    HotelView createHotel(@Valid @RequestBody HotelBody body) {
        return HotelView.of(hotels.insert(body.toHotel()));
    }

    @PutMapping("/hotels/{id}")
    @Transactional
    HotelView updateHotel(@PathVariable UUID id, @Valid @RequestBody HotelBody body) {
        // Without "photos" the hotel's photos (managed under /photos) are left as they are.
        if (!hotels.update(id, body.toHotel(), body.photos() != null)) {
            throw new NotFoundException("hotel", id);
        }
        return hotel(id);
    }

    /** Deactivates the hotel: it leaves search, its negotiations keep their history. */
    @DeleteMapping("/hotels/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteHotel(@PathVariable UUID id) {
        if (!hotels.deactivate(id)) {
            throw new NotFoundException("hotel", id);
        }
    }

    // Room types

    @GetMapping("/hotels/{hotelId}/room-types")
    List<RoomTypeView> roomTypes(@PathVariable UUID hotelId) {
        requireHotel(hotelId);
        return roomTypes.forHotel(hotelId).stream().map(RoomTypeView::of).toList();
    }

    @PostMapping("/hotels/{hotelId}/room-types")
    @ResponseStatus(HttpStatus.CREATED)
    RoomTypeView createRoomType(@PathVariable UUID hotelId, @Valid @RequestBody RoomTypeBody body) {
        requireHotel(hotelId);
        return RoomTypeView.of(roomTypes.insert(hotelId, body.name(), body.listPrice(), body.maxGuests()));
    }

    @PutMapping("/hotels/{hotelId}/room-types/{id}")
    @Transactional
    RoomTypeView updateRoomType(@PathVariable UUID hotelId, @PathVariable UUID id,
            @Valid @RequestBody RoomTypeBody body) {
        requireRoomType(hotelId, id);
        floorRules.forRoomType(id).stream().filter(r -> r.floorPrice() > body.listPrice()).findAny().ifPresent(r -> {
            throw new InvalidStayException("list price " + body.listPrice() + " is below an existing floor rule");
        });
        roomTypes.update(id, body.name(), body.listPrice(), body.maxGuests());
        return RoomTypeView.of(roomTypes.find(id).orElseThrow());
    }

    @DeleteMapping("/hotels/{hotelId}/room-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteRoomType(@PathVariable UUID hotelId, @PathVariable UUID id) {
        requireRoomType(hotelId, id);
        roomTypes.delete(id);
    }

    // Floor rules

    @GetMapping("/room-types/{roomTypeId}/floor-rules")
    List<FloorRule> floorRules(@PathVariable UUID roomTypeId) {
        roomType(roomTypeId);
        return floorRules.forRoomType(roomTypeId);
    }

    @PostMapping("/room-types/{roomTypeId}/floor-rules")
    @ResponseStatus(HttpStatus.CREATED)
    FloorRule createFloorRule(@PathVariable UUID roomTypeId, @Valid @RequestBody FloorRuleBody body) {
        return floorRules.insert(checked(roomType(roomTypeId), body));
    }

    @PutMapping("/room-types/{roomTypeId}/floor-rules/{id}")
    FloorRule updateFloorRule(@PathVariable UUID roomTypeId, @PathVariable UUID id,
            @Valid @RequestBody FloorRuleBody body) {
        FloorRule rule = checked(roomType(roomTypeId), body);
        if (!floorRules.update(id, rule)) {
            throw new NotFoundException("floor rule", id);
        }
        return floorRules.find(id).orElseThrow();
    }

    @DeleteMapping("/room-types/{roomTypeId}/floor-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteFloorRule(@PathVariable UUID roomTypeId, @PathVariable UUID id) {
        if (!floorRules.delete(roomTypeId, id)) {
            throw new NotFoundException("floor rule", id);
        }
    }

    // Availability

    @PutMapping("/hotels/{hotelId}/availability/{nightOf}")
    AvailabilityView putAvailability(@PathVariable UUID hotelId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate nightOf,
            @Valid @RequestBody AvailabilityBody body) {
        requireHotel(hotelId);
        availability.upsert(hotelId, nightOf, body.roomsFree(), AvailabilitySource.ADMIN);
        return new AvailabilityView(hotelId, nightOf, body.roomsFree(), AvailabilitySource.ADMIN);
    }

    private void requireHotel(UUID id) {
        hotels.find(id).orElseThrow(() -> new NotFoundException("hotel", id));
    }

    private RoomType roomType(UUID id) {
        return roomTypes.find(id).orElseThrow(() -> new NotFoundException("room type", id));
    }

    private void requireRoomType(UUID hotelId, UUID id) {
        if (!roomType(id).hotelId().equals(hotelId)) {
            throw new NotFoundException("room type", id);
        }
    }

    private static FloorRule checked(RoomType room, FloorRuleBody body) {
        if ((body.seasonStart() == null) != (body.seasonEnd() == null)) {
            throw new InvalidStayException("give both seasonStart and seasonEnd, or neither");
        }
        if (body.seasonStart() != null && body.seasonStart().isAfter(body.seasonEnd())) {
            throw new InvalidStayException("seasonStart is after seasonEnd");
        }
        if (body.floorPrice() > room.listPrice()) {
            throw new InvalidStayException("floor price is above the list price " + room.listPrice());
        }
        return body.toRule(room.id());
    }
}
