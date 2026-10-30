package com.latestay.stay;

import com.latestay.hotel.FloorRule;
import com.latestay.hotel.FloorRuleResolver;
import com.latestay.hotel.RoomType;
import com.latestay.negotiation.PricingContext;
import com.latestay.negotiation.PricingPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pricing policy for one or two rooms on a stay: list = the sum of their list prices, floor = the sum of their
 * floors (each from its own floor rule). Empty when any room has no floor rule for the stay. Search and new
 * negotiations both build policies here, so a "from" price is the price the negotiation opens at.
 */
public final class StayPricing {

    private StayPricing() {
    }

    public static Optional<PricingPolicy> policy(List<RoomType> rooms, Map<java.util.UUID, List<FloorRule>> rules,
            StayWindow window, PricingContext context) {
        long list = 0;
        long floor = 0;
        for (RoomType room : rooms) {
            Optional<FloorRule> rule = FloorRuleResolver.resolve(rules.getOrDefault(room.id(), List.of()),
                    window.slotType(), window.nightOf(), window.hours());
            if (rule.isEmpty()) {
                return Optional.empty();
            }
            list += room.listPrice();
            floor += rule.get().floorPrice();
        }
        try {
            return Optional.of(PricingPolicy.of(list, floor, window.slotType(), context));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
