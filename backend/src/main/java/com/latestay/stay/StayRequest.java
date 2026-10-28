package com.latestay.stay;

import java.util.List;
import java.util.UUID;

public record StayRequest(UUID id, double lat, double lng, StayWindow window, int guests, List<SearchFilter> filters) {

    public StayRequest {
        filters = List.copyOf(filters);
    }
}
