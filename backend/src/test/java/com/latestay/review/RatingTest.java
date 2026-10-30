package com.latestay.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RatingTest {

    @Test
    void newUntilThreeReviews() {
        assertThat(Rating.of(0, 0).value()).isNull();
        assertThat(Rating.of(2, 10).value()).isNull();
        assertThat(Rating.of(2, 10).count()).isEqualTo(2);
        assertThat(Rating.of(3, 15).value()).isNotNull();
    }

    @Test
    void bayesianAverageStartsFromFourWeightedAsFiveReviews() {
        // (5 x 4.0 + 15) / 8 = 4.375
        assertThat(Rating.of(3, 15).value()).isEqualByComparingTo(new BigDecimal("4.4"));
        // (20 + 3) / 8 = 2.875: three 1-star reviews still don't read as 1.0
        assertThat(Rating.of(3, 3).value()).isEqualByComparingTo(new BigDecimal("2.9"));
        // Many reviews approach their own mean: 100 x 5 stars -> (20 + 500) / 105 = 4.95
        assertThat(Rating.of(100, 500).value()).isEqualByComparingTo(new BigDecimal("5.0"));
    }

    @Test
    void oneReviewCannotSwingIt() {
        BigDecimal before = Rating.of(3, 15).value();
        BigDecimal after = Rating.of(4, 16).value(); // a 1-star review on top
        assertThat(before.subtract(after).abs()).isLessThanOrEqualTo(new BigDecimal("0.5"));
    }
}
