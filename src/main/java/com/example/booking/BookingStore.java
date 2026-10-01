package com.example.booking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import static com.example.booking.Models.*;

@Repository
public class BookingStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public BookingStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(5);
    }
    public JdbcTemplate jdbc() { return jdbc; }
    <T> T tx(Supplier<T> work) {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            jdbc.execute("SET LOCAL statement_timeout = '3s'");
            return work.get();
        });
    }
    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }
    static final RowMapper<BookingRow> BOOKING = (rs, n) -> new BookingRow(
        rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class), rs.getInt("seat_number"),
        rs.getString("buyer_id"), rs.getString("state"), rs.getString("reconciliation"),
        instant(rs, "expires_at"), instant(rs, "created_at"), rs.getString("hold_key"), rs.getInt("ttl_seconds"));
    static final RowMapper<PaymentRow> PAYMENT = (rs, n) -> new PaymentRow(new Payment(
        rs.getObject("id", UUID.class), rs.getObject("booking_id", UUID.class),
        Scenario.valueOf(rs.getString("scenario")), rs.getInt("delay_ms"), rs.getString("state"),
        instant(rs, "ready_at"), rs.getInt("attempts")), rs.getString("checkout_key"), rs.getObject("lease_token", UUID.class));
    BookingRow row(UUID id) {
        return one(jdbc.query("SELECT * FROM bookings WHERE id = ?", BOOKING, id));
    }
    BookingRow locked(UUID id) {
        // Immutable locator first; every inventory mutation acquires seat before booking.
        BookingRow locator = row(id);
        lockSeat(locator.eventId(), locator.seatNumber());
        return one(jdbc.query("SELECT * FROM bookings WHERE id = ? FOR UPDATE", BOOKING, id));
    }
    void lockSeat(UUID event, int seat) {
        if (jdbc.queryForList("SELECT seat_number FROM seats WHERE event_id = ? AND seat_number = ? FOR UPDATE", event, seat).isEmpty()) {
            throw ApiException.missing();
        }
    }
    PaymentRow paymentRow(UUID id) {
        return one(jdbc.query("SELECT * FROM payments WHERE id = ?", PAYMENT, id));
    }
    Payment paymentFor(UUID booking) {
        var rows = jdbc.query("SELECT * FROM payments WHERE booking_id = ?", PAYMENT, booking);
        return rows.isEmpty() ? null : rows.getFirst().payment();
    }
    Booking view(UUID id, boolean replayed) {
        BookingRow b = row(id);
        return new Booking(b.id(), b.eventId(), b.seatNumber(), b.buyerId(), b.state(), b.reconciliation(),
                           b.expiresAt(), b.createdAt(), paymentFor(id), replayed);
    }
    void audit(UUID booking, String action) {
        jdbc.update("INSERT INTO booking_audit(booking_id, action) VALUES (?, ?)", booking, action);
    }
    void enqueueSnapshot(UUID booking,String kind) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox snapshot must join the inventory transaction");
        jdbc.update("""
            INSERT INTO booking_outbox(id,booking_id,booking_version,kind)
            SELECT ?,id,delivery_version,? FROM bookings WHERE id=?
            """,UUID.randomUUID(),kind,booking);
    }
    void expire(UUID booking) {
        if (jdbc.update("""
            UPDATE bookings SET state = 'EXPIRED', updated_at = clock_timestamp()
            WHERE id = ? AND state IN ('HELD','CHECKOUT') AND expires_at <= clock_timestamp()
            """, booking) == 1) audit(booking, "EXPIRED");
    }
    static <T> T one(List<T> rows) {
        if (rows.isEmpty()) throw ApiException.missing();
        return rows.getFirst();
    }
}
