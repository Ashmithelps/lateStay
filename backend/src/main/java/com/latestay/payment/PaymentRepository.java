package com.latestay.payment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    private static final String COLUMNS = """
            id, booking_id, razorpay_order_id, razorpay_payment_id, amount, status, refund_id, refund_attempts,
            last_error""";

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Payment insert(UUID bookingId, String orderId, long amount, Instant now) {
        long id = jdbc.sql("""
                        INSERT INTO payment (booking_id, razorpay_order_id, amount, status, created_at, updated_at)
                        VALUES (:booking, :order, :amount, 'CREATED', :now, :now) RETURNING id""")
                .param("booking", bookingId).param("order", orderId).param("amount", amount)
                .param("now", at(now)).query(Long.class).single();
        return new Payment(id, bookingId, orderId, null, amount, PaymentStatus.CREATED, null, 0, null);
    }

    public Optional<Payment> forBooking(UUID bookingId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE booking_id = :booking ORDER BY id DESC LIMIT 1")
                .param("booking", bookingId).query(PaymentRepository::map).optional();
    }

    public Optional<Payment> lockByOrderId(String orderId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE razorpay_order_id = :order FOR UPDATE")
                .param("order", orderId).query(PaymentRepository::map).optional();
    }

    public Optional<Payment> lockByPaymentId(String paymentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment WHERE razorpay_payment_id = :payment FOR UPDATE")
                .param("payment", paymentId).query(PaymentRepository::map).optional();
    }

    public Optional<Payment> lockForBooking(UUID bookingId) {
        return jdbc.sql("""
                        SELECT %s FROM payment WHERE booking_id = :booking ORDER BY id DESC LIMIT 1
                        FOR UPDATE""".formatted(COLUMNS))
                .param("booking", bookingId).query(PaymentRepository::map).optional();
    }

    /** Payments waiting for the refund job, locked; rows another worker holds are skipped. */
    public List<Payment> lockRefundsPending(int limit) {
        return jdbc.sql("""
                        SELECT %s FROM payment WHERE status = 'REFUND_PENDING' ORDER BY updated_at LIMIT :limit
                        FOR UPDATE SKIP LOCKED""".formatted(COLUMNS))
                .param("limit", limit).query(PaymentRepository::map).list();
    }

    public void captured(long id, String paymentId, String rawEvent, Instant now) {
        jdbc.sql("""
                        UPDATE payment SET status = 'CAPTURED', razorpay_payment_id = :payment,
                               raw_event = COALESCE(CAST(:raw AS jsonb), raw_event), updated_at = :now
                        WHERE id = :id""")
                .param("id", id).param("payment", paymentId).param("raw", rawEvent).param("now", at(now)).update();
    }

    public void status(long id, PaymentStatus status, Instant now) {
        jdbc.sql("UPDATE payment SET status = :status, updated_at = :now WHERE id = :id")
                .param("id", id).param("status", status.name()).param("now", at(now)).update();
    }

    public void refundRequested(long id, String refundId, Instant now) {
        jdbc.sql("""
                        UPDATE payment SET status = 'REFUND_REQUESTED', refund_id = :refund,
                               refund_attempts = refund_attempts + 1, last_error = NULL, updated_at = :now
                        WHERE id = :id""")
                .param("id", id).param("refund", refundId).param("now", at(now)).update();
    }

    public void refundFailed(long id, String error, Instant now) {
        jdbc.sql("""
                        UPDATE payment SET refund_attempts = refund_attempts + 1, last_error = :error, updated_at = :now
                        WHERE id = :id""")
                .param("id", id).param("error", error).param("now", at(now)).update();
    }

    public void refunded(long id, String refundId, String rawEvent, Instant now) {
        jdbc.sql("""
                        UPDATE payment SET status = 'REFUNDED', refund_id = COALESCE(:refund, refund_id),
                               raw_event = COALESCE(CAST(:raw AS jsonb), raw_event), updated_at = :now
                        WHERE id = :id""")
                .param("id", id).param("refund", refundId).param("raw", rawEvent).param("now", at(now)).update();
    }

    public void rawEvent(long id, String rawEvent, Instant now) {
        jdbc.sql("UPDATE payment SET raw_event = CAST(:raw AS jsonb), updated_at = :now WHERE id = :id")
                .param("id", id).param("raw", rawEvent).param("now", at(now)).update();
    }

    private static java.time.OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Payment map(ResultSet rs, int n) throws SQLException {
        return new Payment(rs.getLong("id"), rs.getObject("booking_id", UUID.class),
                rs.getString("razorpay_order_id"), rs.getString("razorpay_payment_id"), rs.getLong("amount"),
                PaymentStatus.valueOf(rs.getString("status")), rs.getString("refund_id"),
                rs.getInt("refund_attempts"), rs.getString("last_error"));
    }
}
