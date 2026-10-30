package com.latestay.review;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    /** @param text optional, up to 300 characters, no phone numbers */
    public record ReviewBody(@NotNull @Min(1) @Max(5) Integer stars, @Size(max = 5) List<@NotNull ReviewTag> tags,
            @Schema(nullable = true) @Size(max = 600) String text) {
    }

    public enum ReviewStatus {
        /** The stay hasn't ended yet. */
        NOT_YET,
        OPEN,
        DONE,
        /** More than 14 days after the stay. */
        CLOSED,
        /** The booking didn't end in a stay (cancelled, refunded, no-show, disputed...). */
        NOT_ELIGIBLE
    }

    /**
     * What the pass shows about reviewing this booking.
     *
     * @param closesAt the last moment a review can be left (while OPEN)
     */
    public record ReviewState(ReviewStatus status, String hotelName, @Schema(nullable = true) Instant closesAt,
            @Schema(nullable = true) MyReview review) {
    }

    public record MyReview(int stars, List<ReviewTag> tags, @Schema(nullable = true) String text) {
    }

    /** A review as shown on a hotel page: no name, only the month of the stay. */
    public record PublicReview(int stars, List<ReviewTag> tags, @Schema(nullable = true) String text,
            YearMonth stayedIn) {
    }

    public record TagCount(ReviewTag tag, int count) {
    }

    /** @param recent up to 5 of the latest reviews, newest first */
    public record HotelReviews(Rating rating, List<TagCount> tags, List<PublicReview> recent) {
    }

    /** For /admin: the text is always included, with whether it's hidden. */
    public record AdminReview(UUID id, UUID bookingId, UUID hotelId, String hotelName, int stars, List<ReviewTag> tags,
            String text, boolean textHidden, Instant createdAt) {
    }
}
