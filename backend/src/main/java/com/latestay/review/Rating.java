package com.latestay.review;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A hotel's rating as shown: "New" ({@code value} null) until it has {@link #MIN_REVIEWS} reviews, then a Bayesian
 * average that starts from {@link #PRIOR_MEAN} weighted as {@link #PRIOR_WEIGHT} reviews, so one review can't swing
 * it. One decimal, half up.
 */
public record Rating(@Schema(nullable = true) BigDecimal value, int count) {

    public static final int MIN_REVIEWS = 3;
    public static final BigDecimal PRIOR_MEAN = new BigDecimal("4.0");
    public static final int PRIOR_WEIGHT = 5;

    public static final Rating NEW = new Rating(null, 0);

    public static Rating of(int count, long sumOfStars) {
        if (count < MIN_REVIEWS) {
            return new Rating(null, count);
        }
        BigDecimal total = PRIOR_MEAN.multiply(BigDecimal.valueOf(PRIOR_WEIGHT)).add(BigDecimal.valueOf(sumOfStars));
        return new Rating(total.divide(BigDecimal.valueOf(PRIOR_WEIGHT + count), 1, RoundingMode.HALF_UP), count);
    }
}
