package com.latestay.dev.negotiation;

import com.latestay.negotiation.SlotType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalTime;

record CreateNegotiationRequest(
        @NotNull @Positive Long listPrice,
        @NotNull @Positive Long floorPrice,
        @NotNull SlotType slotType,
        @NotNull LocalTime arrival) {
}
