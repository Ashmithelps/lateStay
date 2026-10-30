package com.latestay.booking;

import static com.latestay.booking.BookingStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every one of the 13 x 13 status pairs: the legal ones pass the guard, every other one is rejected. */
class BookingTransitionsTest {

    static final Map<BookingStatus, Set<BookingStatus>> EXPECTED = Map.ofEntries(
            Map.entry(PENDING_PAYMENT, EnumSet.of(DEPOSIT_PAID, EXPIRED, CANCELLED)),
            Map.entry(DEPOSIT_PAID, EnumSet.of(HOTEL_CONFIRMED, HOTEL_DECLINED, HOTEL_TIMEOUT, CANCELLED, HOTEL_FAILED)),
            Map.entry(HOTEL_CONFIRMED, EnumSet.of(CHECKED_IN, NO_SHOW, COMPLETED, DISPUTED, HOTEL_FAILED)),
            Map.entry(CHECKED_IN, EnumSet.of(COMPLETED, HOTEL_FAILED)),
            Map.entry(NO_SHOW, EnumSet.of(CHECKED_IN, DISPUTED, HOTEL_FAILED)),
            Map.entry(DISPUTED, EnumSet.of(COMPLETED, NO_SHOW, HOTEL_FAILED)),
            Map.entry(COMPLETED, EnumSet.of(HOTEL_FAILED)),
            Map.entry(HOTEL_DECLINED, EnumSet.of(REFUNDED)),
            Map.entry(HOTEL_TIMEOUT, EnumSet.of(REFUNDED)),
            Map.entry(CANCELLED, EnumSet.of(REFUNDED)),
            Map.entry(EXPIRED, EnumSet.noneOf(BookingStatus.class)),
            Map.entry(REFUNDED, EnumSet.noneOf(BookingStatus.class)),
            Map.entry(HOTEL_FAILED, EnumSet.noneOf(BookingStatus.class)));

    static Stream<Arguments> legal() {
        return pairs(true);
    }

    static Stream<Arguments> illegal() {
        return pairs(false);
    }

    static Stream<Arguments> pairs(boolean legal) {
        List<Arguments> pairs = new ArrayList<>();
        for (BookingStatus from : BookingStatus.values()) {
            for (BookingStatus to : BookingStatus.values()) {
                if (EXPECTED.get(from).contains(to) == legal) {
                    pairs.add(Arguments.of(from, to));
                }
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @MethodSource("legal")
    void legalTransitionsPass(BookingStatus from, BookingStatus to) {
        assertThat(BookingTransitions.allowed(from, to)).isTrue();
        assertThatCode(() -> BookingTransitions.guard(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @MethodSource("illegal")
    void illegalTransitionsAreRejected(BookingStatus from, BookingStatus to) {
        assertThat(BookingTransitions.allowed(from, to)).isFalse();
        assertThatThrownBy(() -> BookingTransitions.guard(from, to))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessage("booking can't go from " + from + " to " + to);
    }

    @Test
    void everyStatusIsCoveredAndThePairsAddUp() {
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(BookingStatus.values());
        assertThat(legal().count()).isEqualTo(25);
        assertThat(legal().count() + illegal().count()).isEqualTo(169);
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(BookingTransitions.from(status)).isEqualTo(EXPECTED.get(status));
        }
    }

    @Test
    void noStatusMovesToItself() {
        for (BookingStatus status : BookingStatus.values()) {
            assertThat(BookingTransitions.allowed(status, status)).as(status.name()).isFalse();
        }
    }

    @Test
    void terminalStatusesGoNowhere() {
        for (BookingStatus status : List.of(EXPIRED, REFUNDED, HOTEL_FAILED)) {
            assertThat(BookingTransitions.from(status)).as(status.name()).isEmpty();
        }
    }
}
