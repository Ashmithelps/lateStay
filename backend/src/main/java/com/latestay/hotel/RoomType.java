package com.latestay.hotel;

import java.util.UUID;

public record RoomType(UUID id, UUID hotelId, String name, long listPrice, int maxGuests) {
}
