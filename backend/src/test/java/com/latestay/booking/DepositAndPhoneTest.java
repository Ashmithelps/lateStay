package com.latestay.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DepositAndPhoneTest {

    @ParameterizedTest(name = "agreed {0}: deposit {1}, at desk {2}, commission {3}")
    @CsvSource({
            "900, 200, 700, 100",
            "200, 200, 0, 100",
            "150, 150, 0, 100",
            "80, 80, 0, 80",
            "2400, 200, 2200, 100"})
    void depositIsCappedAt200AndTheRestIsPaidAtTheDesk(long agreed, long deposit, long desk, long commission) {
        Deposit split = Deposit.of(agreed, 200, 100);
        assertThat(split.deposit()).isEqualTo(deposit);
        assertThat(split.payAtDesk()).isEqualTo(desk);
        assertThat(split.commission()).isEqualTo(commission);
        assertThat(split.deposit() + split.payAtDesk()).isEqualTo(agreed);
    }

    @Test
    void agreedPriceMustBePositive() {
        assertThatThrownBy(() -> Deposit.of(0, 200, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "98765 43210", "+91 98765 43210", "+919876543210", "919876543210",
            "09876543210", "98765-43210", " 6123456789 "})
    void acceptsIndianMobileNumbers(String input) {
        assertThat(IndianMobile.normalise(input)).hasValueSatisfying(p -> assertThat(p).matches("\\+91[6-9]\\d{9}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "5876543210", "987654321", "98765432101", "+1 415 555 0100",
            "+92 3001234567", "98765abcde", "0120 4567890"})
    void rejectsEverythingElse(String input) {
        assertThat(IndianMobile.normalise(input)).isEmpty();
    }

    @Test
    void normalisesToE164() {
        assertThat(IndianMobile.normalise("098765 43210")).contains("+919876543210");
    }

    @Test
    void newCodesAreFourDigitsAndAvoidCodesInUse() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < 9_990; i++) {
            used.add("%04d".formatted(i));
        }
        for (int i = 0; i < 50; i++) {
            String code = BookingService.newCode(used);
            assertThat(code).matches("\\d{4}");
            assertThat(Integer.parseInt(code)).isGreaterThanOrEqualTo(9_990);
        }
    }
}
