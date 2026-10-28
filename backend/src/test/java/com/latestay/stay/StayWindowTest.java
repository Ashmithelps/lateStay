package com.latestay.stay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.latestay.negotiation.SlotType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StayWindowTest {

    @ParameterizedTest(name = "{0} -> night of {1}")
    @CsvSource({
            "2026-10-28T21:30, 2026-10-28",
            "2026-10-28T23:59, 2026-10-28",
            "2026-10-29T00:45, 2026-10-28",
            "2026-10-29T05:30, 2026-10-28",
            "2026-10-29T05:59, 2026-10-28",
            "2026-10-29T06:00, 2026-10-28",
            "2026-10-29T08:30, 2026-10-28",
            "2026-10-29T09:00, 2026-10-28",
            "2026-10-29T09:01, 2026-10-29",
            "2026-10-29T12:00, 2026-10-29",
    })
    void arrivalsUpToNineBelongToThePreviousNight(LocalDateTime arrive, LocalDate nightOf) {
        assertThat(StayWindow.nightOf(arrive)).isEqualTo(nightOf);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-28T23:30, 2026-10-29T09:00, LATE_NIGHT, 2026-10-28, 9",
            "2026-10-29T01:00, 2026-10-29T10:00, LATE_NIGHT, 2026-10-28, 9",
            "2026-10-29T05:30, 2026-10-29T14:00, EARLY_MORNING, 2026-10-28, 8",
            "2026-10-29T07:00, 2026-10-29T12:00, EARLY_MORNING, 2026-10-28, 5",
    })
    void validWindows(LocalDateTime arrive, LocalDateTime leave, SlotType slot, LocalDate nightOf, int hours) {
        StayWindow window = StayWindow.of(arrive, leave);

        assertThat(window.slotType()).isEqualTo(slot);
        assertThat(window.nightOf()).isEqualTo(nightOf);
        assertThat(window.hours()).isEqualTo(hours);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-28T23:30, 2026-10-28T23:00",
            "2026-10-28T18:00, 2026-10-29T09:00",
            "2026-10-29T03:30, 2026-10-29T09:00",
            "2026-10-28T23:30, 2026-10-29T10:30",
            "2026-10-29T05:30, 2026-10-29T15:00",
    })
    void invalidWindows(LocalDateTime arrive, LocalDateTime leave) {
        assertThatThrownBy(() -> StayWindow.of(arrive, leave)).isInstanceOf(InvalidStayException.class);
    }
}
