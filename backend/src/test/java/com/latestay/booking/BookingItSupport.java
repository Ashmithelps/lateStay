package com.latestay.booking;

import com.latestay.TestClock;
import com.latestay.TestHotels;
import com.latestay.TestcontainersConfiguration;
import com.latestay.hotel.AvailabilityRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Shared context for the booking integration tests (one Spring context: same properties and imports). */
@SpringBootTest(properties = {"latestay.admin.username=ops", "latestay.admin.password=night-desk"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestHotels.class, BookingTestConfiguration.class})
public abstract class BookingItSupport {

    protected static final String ADMIN_USER = "ops";
    protected static final String ADMIN_PASSWORD = "night-desk";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected TestClock clock;
    @Autowired
    protected RecordingRazorpayGateway gateway;
    @Autowired
    protected TestBookings fixtures;
    @Autowired
    protected TestHotels hotels;
    @Autowired
    protected BookingService bookings;
    @Autowired
    protected BookingJobs jobs;
    @Autowired
    protected AvailabilityRepository availability;
    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    protected void resetClockAndGateway() {
        clock.set(BookingTestConfiguration.START);
        gateway.reset();
    }

    protected BookingStatus statusOf(UUID bookingId) {
        return BookingStatus.valueOf(jdbc.sql("SELECT status FROM booking WHERE id = :id").param("id", bookingId)
                .query(String.class).single());
    }

    /** "FROM->TO by ACTOR" for each logged change, oldest first. */
    protected List<String> events(UUID bookingId) {
        return jdbc.sql("""
                        SELECT coalesce(from_status, '') || '->' || to_status || ' by ' || actor FROM booking_event
                        WHERE booking_id = :id ORDER BY id""")
                .param("id", bookingId).query(String.class).list();
    }

    protected Map<String, Object> payment(UUID bookingId) {
        return jdbc.sql("SELECT *, raw_event::text AS raw FROM payment WHERE booking_id = :id")
                .param("id", bookingId).query().singleRow();
    }

    protected int roomsFree(TestBookings.Agreed agreed) {
        return availability.roomsFree(agreed.hotel().id(), TestBookings.NIGHT);
    }
}
