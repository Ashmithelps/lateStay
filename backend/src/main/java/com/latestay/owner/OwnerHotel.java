package com.latestay.owner;

import java.util.UUID;

/**
 * The hotel a signed-in owner page is for, from the ls_owner cookie only. Owner endpoints take this instead of a
 * hotel id, so no request can name another hotel.
 */
public record OwnerHotel(UUID hotelId, UUID sessionId) {
}
