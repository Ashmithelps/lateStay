package com.latestay.town;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.booking.BookingItSupport;
import com.latestay.booking.TestBookings;
import com.latestay.hotel.Hotel;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Town pages: real agreed prices only, at least 3, quartiles rounded out to ₹50; never floors. */
class TownsIT extends BookingItSupport {

    static final double LAT = 42.0;

    @Autowired
    Towns towns;

    String townOf(Hotel hotel, String name) {
        jdbc.sql("UPDATE hotel SET town = :t WHERE id = :id").param("t", name).param("id", hotel.id()).update();
        return name;
    }

    UUID paidAt(TestBookings.Agreed agreed) {
        BookingCreated created = fixtures.book(agreed);
        fixtures.pay(created);
        return created.bookingId();
    }

    void agreedPrice(UUID booking, long price) {
        jdbc.sql("UPDATE booking SET agreed_price = :p, pay_at_desk = :p - deposit_amount - promo_discount_paise / 100 "
                        + "WHERE id = :id").param("p", price).param("id", booking).update();
    }

    @Test
    void aTownPageListsItsHotelsWithATypicalPriceFromAgreedPrices() throws Exception {
        long n = System.nanoTime() % 100_000;
        String town = "Kasol Heights " + n;
        String slug = "kasol-heights-" + n;
        TestBookings.Agreed first = fixtures.agreed(LAT, 1400, 700, 6);
        townOf(first.hotel(), town);
        UUID a = paidAt(first);
        UUID b = paidAt(fixtures.agreedAnother(first));
        assertThat(towns.page(slug).hotels().getFirst().priceRange()).isNull(); // only 2 so far
        UUID c = paidAt(fixtures.agreedAnother(first));
        UUID d = paidAt(fixtures.agreedAnother(first));
        agreedPrice(a, 900);
        agreedPrice(b, 1000);
        agreedPrice(c, 1130);
        agreedPrice(d, 1260);

        var page = towns.page(slug);
        assertThat(page.name()).isEqualTo(town);
        var range = page.hotels().getFirst().priceRange();
        // Quartiles 975 and 1,163.5, rounded out to 950 and 1,200.
        assertThat(range.low()).isEqualTo(950);
        assertThat(range.high()).isEqualTo(1200);
        assertThat(range.bookings()).isEqualTo(4);
        assertThat(page.priceRange()).isEqualTo(range);
        assertThat(range.low()).isNotEqualTo(700); // the floor never shows

        mvc.perform(get("/api/towns")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == '" + slug + "')].hotels").value(1));
        mvc.perform(get("/api/towns/{slug}", slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hotels[0].reviewCount").value(0))
                .andExpect(jsonPath("$.hotels[0].rating").doesNotExist());
        String body = mvc.perform(get("/api/towns/{slug}", slug)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("floor", "700", "whatsapp", "+91");
    }

    @Test
    void unpaidCancelledAndPausedDontCount() throws Exception {
        long n = System.nanoTime() % 100_000;
        String slug = "quiet-vale-" + n;
        TestBookings.Agreed first = fixtures.agreed(LAT, 1400, 700, 6);
        townOf(first.hotel(), "Quiet Vale " + n);
        paidAt(first);
        paidAt(fixtures.agreedAnother(first));
        fixtures.book(fixtures.agreedAnother(first)); // never paid
        UUID cancelled = paidAt(fixtures.agreedAnother(first));
        bookings.cancel(cancelled);
        assertThat(towns.page(slug).hotels().getFirst().priceRange()).isNull();

        jdbc.sql("UPDATE hotel SET reliability_paused_at = now() WHERE id = :id").param("id", first.hotel().id())
                .update();
        mvc.perform(get("/api/towns/{slug}", slug)).andExpect(status().isNotFound());
    }

    @Test
    void roundingNeverGoesBelowFifty() {
        assertThat(Towns.range(2, 100, 200)).isNull();
        assertThat(Towns.range(3, 1001, 1001)).isEqualTo(new Towns.PriceRange(1000, 1050, 3));
        assertThat(Towns.range(3, 1000, 1000)).isEqualTo(new Towns.PriceRange(1000, 1000, 3));
    }
}
