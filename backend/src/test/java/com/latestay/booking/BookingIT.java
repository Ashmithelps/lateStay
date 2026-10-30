package com.latestay.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.latestay.booking.BookingDtos.BookingCreated;
import com.latestay.hotel.Hotel;
import com.latestay.hotel.HotelPolicies;
import com.latestay.negotiation.SlotType;
import com.latestay.payment.FakeRazorpayGateway;
import com.latestay.payment.PaymentStatus;
import com.latestay.session.NegotiationService;
import com.latestay.stay.StayWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class BookingIT extends BookingItSupport {

    static final double LAT = 26.0;

    @Autowired
    ObjectMapper json;
    @Autowired
    NegotiationService negotiations;

    /** With this device's "phone verified" cookie for +91 98765 43210 (what the OTP step leaves behind). */
    ResultActions create(UUID sessionId, String name, String phone, boolean idConsent) throws Exception {
        return mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                .cookie(fixtures.phoneCookie(TestBookings.PHONE)).content(json.writeValueAsString(
                Map.of("sessionId", sessionId, "guestName", name, "phone", phone, "idConsent", idConsent))));
    }

    ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)));
    }

    ResultActions verify(BookingCreated created, String paymentId, String signature) throws Exception {
        return mvc.perform(post("/api/bookings/{id}/verify", created.bookingId()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("orderId", created.checkout().orderId(),
                        "paymentId", paymentId, "signature", signature))));
    }

    // Creating

    @Test
    void bookingFromAnAgreedNegotiationHoldsARoomAndOpensAnOrderForTheDeposit() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        assertThat(agreed.price()).isEqualTo(1000);

        String body = create(agreed.sessionId(), "  Asha   Verma ", "+91 98765 43210", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.checkout.mode").value("FAKE"))
                .andExpect(jsonPath("$.checkout.keyId").value(FakeRazorpayGateway.KEY_ID))
                .andExpect(jsonPath("$.checkout.amount").value(200))
                .andExpect(jsonPath("$.checkout.amountPaise").value(20000))
                .andExpect(jsonPath("$.checkout.currency").value("INR"))
                .andExpect(jsonPath("$.checkout.name").value("Asha Verma"))
                .andExpect(jsonPath("$.checkout.phone").value("+919876543210"))
                .andExpect(jsonPath("$.checkout.timeoutSeconds").value(600))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("bookingId").asText());

        Map<String, Object> row = jdbc.sql("SELECT * FROM booking WHERE id = :id").param("id", id).query().singleRow();
        assertThat(row).containsEntry("agreed_price", 1000).containsEntry("deposit_amount", 200)
                .containsEntry("pay_at_desk", 800).containsEntry("commission_amount", 100)
                .containsEntry("guests", 2).containsEntry("night_of", java.sql.Date.valueOf(TestBookings.NIGHT));
        assertThat((String) row.get("code")).matches("\\d{4}");
        assertThat(roomsFree(agreed)).isEqualTo(1);
        assertThat(payment(id)).containsEntry("status", "CREATED").containsEntry("amount", 200);
        assertThat(events(id)).containsExactly("->PENDING_PAYMENT by TRAVELLER");

        mvc.perform(get("/api/bookings/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.hotel.name").value("Booking Lodge"))
                .andExpect(jsonPath("$.hotel.phone").value(agreed.hotel().whatsappNumber()))
                .andExpect(jsonPath("$.arriveAt").value("2026-11-11T23:30:00"))
                .andExpect(jsonPath("$.arriveBy").value("2026-11-12T00:30:00"))
                .andExpect(jsonPath("$.leaveAt").value("2026-11-12T09:00:00"))
                .andExpect(jsonPath("$.depositAmount").value(200))
                .andExpect(jsonPath("$.payAtDesk").value(800))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.nextBest").doesNotExist());
    }

    @Test
    void previewSplitsTheAgreedPriceBeforeBookingAndThenPointsAtTheBooking() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        mvc.perform(get("/api/negotiations/{id}/booking-preview", agreed.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreedPrice").value(1000))
                .andExpect(jsonPath("$.depositAmount").value(200))
                .andExpect(jsonPath("$.payAtDesk").value(800))
                .andExpect(jsonPath("$.guests").value(2))
                .andExpect(jsonPath("$.arriveAt").value("2026-11-11T23:30:00"))
                .andExpect(jsonPath("$.arriveBy").value("2026-11-12T00:30:00"))
                .andExpect(jsonPath("$.hotel.name").value("Booking Lodge"))
                .andExpect(jsonPath("$.booking").doesNotExist());

        BookingCreated created = fixtures.book(agreed);
        mvc.perform(get("/api/negotiations/{id}/booking-preview", agreed.sessionId()))
                .andExpect(jsonPath("$.booking.id").value(created.bookingId().toString()))
                .andExpect(jsonPath("$.booking.status").value("PENDING_PAYMENT"));

        UUID open = negotiations.create(agreed.stay().id(), agreed.room().id()).id();
        mvc.perform(get("/api/negotiations/{id}/booking-preview", open)).andExpect(status().isConflict());
    }

    @Test
    void smallPricesArePaidInFullOnline() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 180, 120, 1);
        BookingCreated created = fixtures.book(agreed);
        Map<String, Object> row = jdbc.sql("SELECT agreed_price, deposit_amount, pay_at_desk, commission_amount "
                + "FROM booking WHERE id = :id").param("id", created.bookingId()).query().singleRow();
        int price = (Integer) row.get("agreed_price");
        assertThat(price).isLessThanOrEqualTo(200);
        assertThat(row).containsEntry("deposit_amount", price).containsEntry("pay_at_desk", 0);
        assertThat(created.checkout().amount()).isEqualTo(price);
    }

    @Test
    void bookingAgainWhileUnpaidReturnsTheSameOrderWithTheNewDetails() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated first = fixtures.book(agreed);
        clock.advance(java.time.Duration.ofMinutes(3));

        create(agreed.sessionId(), "Asha V", "+91 98765-43210", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(first.bookingId().toString()))
                .andExpect(jsonPath("$.checkout.orderId").value(first.checkout().orderId()))
                .andExpect(jsonPath("$.checkout.name").value("Asha V"))
                .andExpect(jsonPath("$.checkout.timeoutSeconds").value(420));
        assertThat(roomsFree(agreed)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM booking WHERE session_id = :s").param("s", agreed.sessionId())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void oncePaidBookingAgainPointsAtTheExistingBooking() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);
        fixtures.pay(created);

        create(agreed.sessionId(), "Asha Verma", "9876543210", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(created.bookingId().toString()))
                .andExpect(jsonPath("$.status").value("DEPOSIT_PAID"))
                .andExpect(jsonPath("$.checkout").doesNotExist());
    }

    @Test
    void anExpiredUnpaidBookingIsReplacedByANewOne() {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated first = fixtures.book(agreed);
        clock.advance(java.time.Duration.ofMinutes(10));

        BookingCreated second = fixtures.book(agreed);
        assertThat(second.bookingId()).isNotEqualTo(first.bookingId());
        assertThat(statusOf(first.bookingId())).isEqualTo(BookingStatus.EXPIRED);
        assertThat(second.checkout().orderId()).isNotEqualTo(first.checkout().orderId());
        assertThat(roomsFree(agreed)).isEqualTo(1);
    }

    @Test
    void onlyAgreedNegotiationsCanBeBooked() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        UUID open = negotiations.create(agreed.stay().id(), agreed.room().id()).id();

        create(open, "Asha Verma", "9876543210", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("no price has been agreed in this negotiation"));
        create(UUID.randomUUID(), "Asha Verma", "9876543210", true).andExpect(status().isNotFound());
    }

    @Test
    void guestDetailsAreValidated() throws Exception {
        UUID session = fixtures.agreed(LAT, 1400, 700, 2).sessionId();
        create(session, "Asha Verma", "12345", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("enter a 10-digit Indian mobile number"));
        create(session, "Asha Verma", "+1 415 555 0100", true).andExpect(status().isBadRequest());
        create(session, "Asha Verma", "9876543210", false).andExpect(status().isBadRequest());
        create(session, "   ", "9876543210", true).andExpect(status().isBadRequest());
        create(session, "x".repeat(81), "9876543210", true).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM booking WHERE session_id = :s").param("s", session)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void noRoomsLeftMeansNoBooking() throws Exception {
        // The agreement's hold runs out after 10 minutes, and meanwhile the owner reports no rooms.
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 1);
        clock.advance(java.time.Duration.ofMinutes(10));
        hotels.roomsFree(agreed.hotel(), TestBookings.NIGHT, 0);
        create(agreed.sessionId(), "Asha Verma", "9876543210", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("the hotel has no rooms left for this night"));
    }

    @Test
    void theArrivalTimeMustNotHavePassed() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 1);
        clock.set(Instant.parse("2026-11-11T19:00:00Z")); // 00:30 IST, the arrive-by time
        create(agreed.sessionId(), "Asha Verma", "9876543210", true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("the arrival time for this stay has passed"));
    }

    @Test
    void arriveByIsAnHourAfterArrivalCappedByTheSlotWindowAndTheLeaveTime() {
        LocalDate night = LocalDate.of(2026, 11, 11);
        assertThat(bookings.arriveBy(StayWindow.of(night.atTime(23, 30), night.plusDays(1).atTime(9, 0))))
                .isEqualTo(Instant.parse("2026-11-11T19:00:00Z"));
        // 02:30 arrival: the late-night window ends at 03:00.
        assertThat(bookings.arriveBy(StayWindow.of(night.plusDays(1).atTime(2, 30), night.plusDays(1).atTime(9, 0))))
                .isEqualTo(LocalDateTime.of(2026, 11, 12, 3, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
        // 05:00 arrival leaving at 05:30.
        assertThat(bookings.arriveBy(StayWindow.of(night.atTime(5, 0), night.atTime(5, 30))))
                .isEqualTo(LocalDateTime.of(2026, 11, 11, 5, 30).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
    }

    // Paying

    @Test
    void checkoutSignatureIsVerifiedBeforeTheDepositCounts() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);
        String paymentId = "pay_checkout_1";

        verify(created, paymentId, "0".repeat(64))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("the payment couldn't be verified"));
        verify(created, paymentId, FakeRazorpayGateway.sign(created.checkout().orderId(), "pay_other"))
                .andExpect(status().isBadRequest());
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.PENDING_PAYMENT);

        verify(created, paymentId, FakeRazorpayGateway.sign(created.checkout().orderId(), paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEPOSIT_PAID"))
                .andExpect(jsonPath("$.confirmBy").value("2026-11-11T17:05:00Z"))
                .andExpect(jsonPath("$.code").doesNotExist());
        assertThat(payment(created.bookingId())).containsEntry("status", "CAPTURED")
                .containsEntry("razorpay_payment_id", paymentId);

        // Checkout's handler can fire twice; the second time changes nothing.
        verify(created, paymentId, FakeRazorpayGateway.sign(created.checkout().orderId(), paymentId))
                .andExpect(status().isOk());
        assertThat(events(created.bookingId()))
                .containsExactly("->PENDING_PAYMENT by TRAVELLER", "PENDING_PAYMENT->DEPOSIT_PAID by TRAVELLER");
    }

    @Test
    void anOrderOnlyVerifiesForItsOwnBooking() throws Exception {
        BookingCreated mine = fixtures.book(fixtures.agreed(LAT, 1400, 700, 2));
        BookingCreated theirs = fixtures.book(fixtures.agreed(LAT, 1400, 700, 2));
        mvc.perform(post("/api/bookings/{id}/verify", mine.bookingId()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("orderId", theirs.checkout().orderId(),
                                "paymentId", "pay_x",
                                "signature", FakeRazorpayGateway.sign(theirs.checkout().orderId(), "pay_x")))))
                .andExpect(status().isBadRequest());
        assertThat(statusOf(theirs.bookingId())).isEqualTo(BookingStatus.PENDING_PAYMENT);
    }

    // The legal moves after payment, end to end

    @Test
    void confirmedBookingShowsTheCodeAndChecksInWithIt() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);
        fixtures.pay(created);
        String code = jdbc.sql("SELECT code FROM booking WHERE id = :id").param("id", created.bookingId())
                .query(String.class).single();

        admin(post("/api/admin/bookings/{id}/confirm", created.bookingId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("HOTEL_CONFIRMED"))
                .andExpect(jsonPath("$.events.length()").value(3));
        mvc.perform(get("/api/bookings/{id}", created.bookingId()))
                .andExpect(jsonPath("$.status").value("HOTEL_CONFIRMED"))
                .andExpect(jsonPath("$.code").value(code));

        String wrong = code.equals("0000") ? "1111" : "0000";
        admin(post("/api/admin/bookings/{id}/check-in", created.bookingId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest());
        admin(post("/api/admin/bookings/{id}/check-in", created.bookingId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("CHECKED_IN"));
        assertThat(events(created.bookingId())).containsExactly("->PENDING_PAYMENT by TRAVELLER",
                "PENDING_PAYMENT->DEPOSIT_PAID by TRAVELLER", "DEPOSIT_PAID->HOTEL_CONFIRMED by ADMIN",
                "HOTEL_CONFIRMED->CHECKED_IN by ADMIN");
        assertThat(roomsFree(agreed)).isEqualTo(1);
    }

    @Test
    void declinedBookingIsRefundedAndPointsAtTheNextBestHotel() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        Hotel nextDoor = hotels.hotel("Next Door Inn", agreed.hotel().lat() + 0.01, agreed.hotel().lng(),
                List.of(), new HotelPolicies(false, false, null), true);
        hotels.room(nextDoor, 1200, 2, 600, SlotType.LATE_NIGHT);
        hotels.roomsFree(nextDoor, TestBookings.NIGHT, 3);
        BookingCreated created = fixtures.book(agreed);
        fixtures.pay(created);

        admin(post("/api/admin/bookings/{id}/decline", created.bookingId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booking.status").value("HOTEL_DECLINED"))
                .andExpect(jsonPath("$.booking.paymentStatus").value("REFUND_PENDING"));
        assertThat(roomsFree(agreed)).isEqualTo(2);
        mvc.perform(get("/api/bookings/{id}", created.bookingId()))
                .andExpect(jsonPath("$.refund.status").value("PENDING"))
                .andExpect(jsonPath("$.refund.amount").value(200));

        jobs.issueRefunds();
        assertThat(gateway.refundCalls(TestBookings.paymentIdFor(created))).isOne();
        mvc.perform(get("/api/bookings/{id}", created.bookingId()))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refund.status").value("PROCESSED"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.nextBest.name").value("Next Door Inn"))
                .andExpect(jsonPath("$.nextBest.slug").value(nextDoor.slug()))
                .andExpect(jsonPath("$.nextBest.stayRequestId").value(agreed.stay().id().toString()))
                .andExpect(jsonPath("$.nextBest.fromPrice").isNumber())
                .andExpect(jsonPath("$.refundedFrom").value("HOTEL_DECLINED"));
        assertThat(events(created.bookingId())).endsWith("DEPOSIT_PAID->HOTEL_DECLINED by ADMIN",
                "HOTEL_DECLINED->REFUNDED by RAZORPAY");
    }

    @Test
    void cancellingAnUnpaidBookingClosesItWithoutARefund() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);

        mvc.perform(post("/api/bookings/{id}/cancel", created.bookingId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund").doesNotExist());
        jobs.issueRefunds();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.CANCELLED);
        assertThat(roomsFree(agreed)).isEqualTo(2);
    }

    @Test
    void cancellingAPaidBookingBeforeConfirmationRefundsInFull() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 2);
        BookingCreated created = fixtures.book(agreed);
        fixtures.pay(created);

        mvc.perform(post("/api/bookings/{id}/cancel", created.bookingId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("PENDING"));
        jobs.issueRefunds();
        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.REFUNDED);
        assertThat(payment(created.bookingId())).containsEntry("status", PaymentStatus.REFUNDED.name());
        assertThat(roomsFree(agreed)).isEqualTo(2);
    }

    // Illegal moves are rejected

    @Test
    void illegalMovesAreRejectedAndChangeNothing() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        BookingCreated created = fixtures.book(agreed);

        admin(post("/api/admin/bookings/{id}/confirm", created.bookingId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("booking can't go from PENDING_PAYMENT to HOTEL_CONFIRMED"));
        admin(post("/api/admin/bookings/{id}/check-in", created.bookingId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"1234\"}"))
                .andExpect(status().isConflict());

        fixtures.pay(created);
        admin(post("/api/admin/bookings/{id}/confirm", created.bookingId())).andExpect(status().isOk());
        admin(post("/api/admin/bookings/{id}/confirm", created.bookingId())).andExpect(status().isConflict());
        admin(post("/api/admin/bookings/{id}/decline", created.bookingId())).andExpect(status().isConflict());
        mvc.perform(post("/api/bookings/{id}/cancel", created.bookingId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("booking can't go from HOTEL_CONFIRMED to CANCELLED"));

        assertThat(statusOf(created.bookingId())).isEqualTo(BookingStatus.HOTEL_CONFIRMED);
        assertThat(events(created.bookingId())).hasSize(3);
        assertThat(gateway.refundCalls(TestBookings.paymentIdFor(created))).isZero();
        assertThatThrownBy(() -> bookings.expireIfUnpaid(UUID.randomUUID()))
                .isInstanceOf(com.latestay.hotel.NotFoundException.class);
    }

    // Admin

    @Test
    void adminEndpointsNeedCredentialsAndThePanelGetsNoBrowserPrompt() throws Exception {
        mvc.perform(get("/api/admin/bookings")).andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(get("/api/admin/bookings").header("X-Requested-With", "fetch"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/api/admin/me").with(httpBasic(ADMIN_USER, "wrong"))).andExpect(status().isUnauthorized());
        admin(get("/api/admin/me")).andExpect(status().isOk()).andExpect(jsonPath("$.username").value(ADMIN_USER));
    }

    @Test
    void adminBoardListsBookingsWithCodesAndPaymentState() throws Exception {
        BookingCreated created = fixtures.book(fixtures.agreed(LAT, 1400, 700, 2));
        fixtures.pay(created);
        String body = admin(get("/api/admin/bookings")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var row = java.util.stream.StreamSupport.stream(json.readTree(body).spliterator(), false)
                .filter(n -> n.path("id").asText().equals(created.bookingId().toString())).findFirst().orElseThrow();
        assertThat(row.path("status").asText()).isEqualTo("DEPOSIT_PAID");
        assertThat(row.path("code").asText()).matches("\\d{4}");
        assertThat(row.path("paymentStatus").asText()).isEqualTo("CAPTURED");
        assertThat(row.path("hotelName").asText()).isEqualTo("Booking Lodge");
        assertThat(row.path("confirmBy").asText()).isEqualTo("2026-11-11T17:05:00Z");
    }

    @Test
    void adminSeesLiveNegotiationsWithTheirFullTranscript() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 3);
        UUID live = negotiations.create(agreed.stay().id(), agreed.room().id()).id();

        String list = admin(get("/api/admin/negotiations")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains(live.toString()).doesNotContain(agreed.sessionId().toString());
        assertThat(admin(get("/api/admin/negotiations").param("state", "recent")).andReturn().getResponse()
                .getContentAsString()).contains(agreed.sessionId().toString());

        admin(get("/api/admin/negotiations/{id}", agreed.sessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.negotiation.status").value("AGREED"))
                .andExpect(jsonPath("$.negotiation.floorPrice").value(700))
                .andExpect(jsonPath("$.turns.length()").value(3))
                .andExpect(jsonPath("$.turns[0].role").value("BOT"))
                .andExpect(jsonPath("$.turns[0].decision").isString())
                .andExpect(jsonPath("$.turns[1].role").value("TRAVELLER"))
                .andExpect(jsonPath("$.turns[1].text").value("Accept ₹1,000"));
    }

    @Test
    void adminSeesRoomsFreePerHotelForTonight() throws Exception {
        TestBookings.Agreed agreed = fixtures.agreed(LAT, 1400, 700, 4);
        String body = admin(get("/api/admin/availability").param("nightOf", "2026-11-11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nightOf").value("2026-11-11"))
                .andReturn().getResponse().getContentAsString();
        var row = java.util.stream.StreamSupport.stream(json.readTree(body).path("hotels").spliterator(), false)
                .filter(n -> n.path("hotelId").asText().equals(agreed.hotel().id().toString())).findFirst()
                .orElseThrow();
        assertThat(row.path("roomsFree").asInt()).isEqualTo(4);
        // 22:30 IST on the 11th is the night of the 11th.
        admin(get("/api/admin/availability")).andExpect(jsonPath("$.nightOf").value("2026-11-11"));
    }
}
