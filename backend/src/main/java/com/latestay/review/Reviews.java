package com.latestay.review;

import com.latestay.booking.Booking;
import com.latestay.booking.BookingRepository;
import com.latestay.booking.BookingStatus;
import com.latestay.booking.InvalidBookingException;
import com.latestay.hotel.ConflictException;
import com.latestay.hotel.HotelRepository;
import com.latestay.hotel.NotFoundException;
import com.latestay.review.ReviewDtos.AdminReview;
import com.latestay.review.ReviewDtos.HotelReviews;
import com.latestay.review.ReviewDtos.MyReview;
import com.latestay.review.ReviewDtos.PublicReview;
import com.latestay.review.ReviewDtos.ReviewBody;
import com.latestay.review.ReviewDtos.ReviewState;
import com.latestay.review.ReviewDtos.ReviewStatus;
import com.latestay.review.ReviewDtos.TagCount;
import com.latestay.stay.LateStayTime;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reviews: one per booking, from the leave time until {@link #WINDOW} after it, for a stay that ended CHECKED_IN or
 * COMPLETED. 1-5 stars, tags from {@link ReviewTag}, optional text up to {@link #MAX_TEXT} characters with no phone
 * numbers. Admins can hide the text; the stars still count.
 */
@Service
public class Reviews {

    public static final Duration WINDOW = Duration.ofDays(14);
    public static final int MAX_TEXT = 300;
    public static final int RECENT = 5;

    private static final Set<BookingStatus> STAYED = Set.of(BookingStatus.CHECKED_IN, BookingStatus.COMPLETED);
    /** Ten or more digits, allowing spaces, dots and dashes between them: a phone number. */
    private static final Pattern PHONE = Pattern.compile("\\d(?:[\\s.()-]*\\d){9,}");

    private final JdbcClient jdbc;
    private final BookingRepository bookings;
    private final HotelRepository hotels;
    private final Ratings ratings;
    private final Clock clock;

    public Reviews(JdbcClient jdbc, BookingRepository bookings, HotelRepository hotels, Ratings ratings, Clock clock) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.hotels = hotels;
        this.ratings = ratings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReviewState state(UUID bookingId) {
        Booking b = bookings.find(bookingId).orElseThrow(() -> new NotFoundException("booking", bookingId));
        String hotelName = hotels.find(b.hotelId()).orElseThrow().name();
        Optional<MyReview> mine = mine(bookingId);
        if (mine.isPresent()) {
            return new ReviewState(ReviewStatus.DONE, hotelName, null, mine.get());
        }
        ReviewStatus status = status(b, clock.instant());
        return new ReviewState(status, hotelName, status == ReviewStatus.OPEN ? b.leaveAt().plus(WINDOW) : null, null);
    }

    /** Whether this booking can be reviewed now (ignoring whether it already has been). */
    static ReviewStatus status(Booking b, Instant now) {
        if (!STAYED.contains(b.status())) {
            return b.status().isLive() ? ReviewStatus.NOT_YET : ReviewStatus.NOT_ELIGIBLE;
        }
        if (now.isBefore(b.leaveAt())) {
            return ReviewStatus.NOT_YET;
        }
        return now.isAfter(b.leaveAt().plus(WINDOW)) ? ReviewStatus.CLOSED : ReviewStatus.OPEN;
    }

    @Transactional
    public MyReview submit(UUID bookingId, ReviewBody body) {
        Booking b = bookings.find(bookingId).orElseThrow(() -> new NotFoundException("booking", bookingId));
        switch (status(b, clock.instant())) {
            case OPEN -> {
            }
            case NOT_YET -> throw new InvalidBookingException("You can rate your stay after you leave.");
            case CLOSED -> throw new InvalidBookingException("Reviews close 14 days after the stay.");
            default -> throw new InvalidBookingException("Only stays that happened can be rated.");
        }
        List<ReviewTag> tags = body.tags() == null ? List.of() : List.copyOf(new LinkedHashSet<>(body.tags()));
        String text = cleanText(body.text());
        try {
            jdbc.sql("""
                            INSERT INTO review (id, booking_id, hotel_id, stars, tags, body, created_at)
                            VALUES (:id, :booking, :hotel, :stars, :tags, :body, :now)""")
                    .param("id", UUID.randomUUID()).param("booking", bookingId).param("hotel", b.hotelId())
                    .param("stars", body.stars()).param("tags", tags.stream().map(Enum::name).toArray(String[]::new))
                    .param("body", text).param("now", at(clock.instant())).update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("This stay has already been rated.");
        }
        return new MyReview(body.stars(), tags, text);
    }

    /** Trimmed, null when empty; refused when too long or when it contains a phone number. */
    static String cleanText(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.codePointCount(0, text.length()) > MAX_TEXT) {
            throw new InvalidBookingException("Please keep it to " + MAX_TEXT + " characters.");
        }
        if (PHONE.matcher(text).find()) {
            throw new InvalidBookingException("Please leave out phone numbers.");
        }
        return text;
    }

    private Optional<MyReview> mine(UUID bookingId) {
        return jdbc.sql("SELECT stars, tags, body FROM review WHERE booking_id = :id").param("id", bookingId)
                .query((rs, n) -> new MyReview(rs.getInt("stars"), tags(rs), rs.getString("body"))).optional();
    }

    /** What a hotel page (and the owner page) shows. */
    @Transactional(readOnly = true)
    public HotelReviews forHotel(UUID hotelId) {
        Map<ReviewTag, Integer> counts = new EnumMap<>(ReviewTag.class);
        jdbc.sql("SELECT unnest(tags) AS tag, count(*) AS n FROM review WHERE hotel_id = :hotel GROUP BY 1")
                .param("hotel", hotelId)
                .query(rs -> {
                    counts.put(ReviewTag.valueOf(rs.getString("tag")), rs.getInt("n"));
                });
        List<TagCount> tags = counts.entrySet().stream()
                .sorted(Map.Entry.<ReviewTag, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new TagCount(e.getKey(), e.getValue())).toList();
        List<PublicReview> recent = jdbc.sql("""
                        SELECT r.stars, r.tags, CASE WHEN r.text_hidden_at IS NULL THEN r.body END AS body,
                               b.leave_at
                        FROM review r JOIN booking b ON b.id = r.booking_id
                        WHERE r.hotel_id = :hotel ORDER BY r.created_at DESC, r.id LIMIT :limit""")
                .param("hotel", hotelId).param("limit", RECENT)
                .query((rs, n) -> new PublicReview(rs.getInt("stars"), tags(rs), rs.getString("body"),
                        YearMonth.from(rs.getObject("leave_at", OffsetDateTime.class).toInstant()
                                .atZone(LateStayTime.ZONE))))
                .list();
        return new HotelReviews(ratings.of(hotelId), tags, recent);
    }

    public List<AdminReview> latest(int limit) {
        return jdbc.sql("""
                        SELECT r.id, r.booking_id, r.hotel_id, h.name, r.stars, r.tags, r.body, r.text_hidden_at,
                               r.created_at
                        FROM review r JOIN hotel h ON h.id = r.hotel_id
                        ORDER BY r.created_at DESC, r.id LIMIT :limit""")
                .param("limit", limit)
                .query((rs, n) -> new AdminReview(rs.getObject("id", UUID.class),
                        rs.getObject("booking_id", UUID.class), rs.getObject("hotel_id", UUID.class),
                        rs.getString("name"), rs.getInt("stars"), tags(rs), rs.getString("body"),
                        rs.getObject("text_hidden_at") != null,
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Hides (or shows again) a review's text. The stars keep counting either way. */
    @Transactional
    public void hideText(UUID reviewId, boolean hidden) {
        int updated = jdbc.sql("UPDATE review SET text_hidden_at = :at WHERE id = :id")
                .param("at", hidden ? at(clock.instant()) : null).param("id", reviewId).update();
        if (updated == 0) {
            throw new NotFoundException("review", reviewId);
        }
    }

    private static List<ReviewTag> tags(ResultSet rs) throws SQLException {
        Array array = rs.getArray("tags");
        return array == null ? List.of()
                : Arrays.stream((String[]) array.getArray()).map(ReviewTag::valueOf).toList();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
