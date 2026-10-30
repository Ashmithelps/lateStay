package com.latestay.hold;

import com.latestay.conversation.RoomGoneException;
import com.latestay.conversation.RoomGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The chat's Accept (or a typed "ok done") takes a room hold before the agreement is saved. */
@Component
class RoomHoldGuard implements RoomGuard {

    private final RoomHolds holds;

    RoomHoldGuard(RoomHolds holds) {
        this.holds = holds;
    }

    @Override
    public void reserve(String sessionId) {
        UUID id = UUID.fromString(sessionId);
        if (holds.take(id).isEmpty()) {
            throw new RoomGoneException(holds.stayOf(id).hotelName());
        }
    }
}
