package com.latestay.booking;

/**
 * How an agreed price splits: the deposit paid online now, the rest at the desk, and LateStay's commission out of
 * the deposit.
 */
public record Deposit(long agreedPrice, long deposit, long payAtDesk, long commission) {

    public static Deposit of(long agreedPrice, long depositCap, long commission) {
        if (agreedPrice <= 0) {
            throw new IllegalArgumentException("agreed price must be positive");
        }
        long deposit = Math.min(depositCap, agreedPrice);
        return new Deposit(agreedPrice, deposit, agreedPrice - deposit, Math.min(commission, deposit));
    }
}
