package com.example.booking;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.example.booking.MovieModels.*;

@Repository
public class MovieStore {
    private final BookingStore db;
    MovieStore(BookingStore db) { this.db=db; }
    public BookingStore db() { return db; }
    static final RowMapper<BookingRow> BOOKING=(rs,n)->new BookingRow(rs.getObject("id",UUID.class),
        rs.getObject("show_id",UUID.class),rs.getString("buyer_id"),rs.getString("state"),
        BookingStore.instant(rs,"expires_at"),rs.getLong("amount_minor"),rs.getString("hold_key"),
        rs.getInt("ttl_seconds"),rs.getObject("current_payment_id",UUID.class));
    static final RowMapper<PaymentRow> PAYMENT=(rs,n)->new PaymentRow(new Payment(rs.getObject("id",UUID.class),
        rs.getObject("booking_id",UUID.class),rs.getInt("payment_number"),rs.getString("state"),
        rs.getInt("provider_step"),rs.getInt("dispatches"),rs.getLong("amount_minor"),rs.getBoolean("refund_required")),
        rs.getString("checkout_key"),rs.getInt("plan_bucket"),rs.getObject("requested_test_bucket",Integer.class),
        rs.getInt("delay_ms"),rs.getObject("lease_token",UUID.class));
    BookingRow row(UUID id) { return BookingStore.one(db.jdbc().query("SELECT * FROM movie_bookings WHERE id=?",BOOKING,id)); }
    PaymentRow payment(UUID id) { return BookingStore.one(db.jdbc().query("SELECT * FROM movie_payments WHERE id=?",PAYMENT,id)); }
    List<SelectedSeat> selected(UUID id) {
        return db.jdbc().query("SELECT seat_number,category,price_minor FROM movie_booking_seats WHERE booking_id=? ORDER BY seat_number",
            (rs,n)->new SelectedSeat(rs.getInt("seat_number"),Category.valueOf(rs.getString("category")),rs.getInt("price_minor")),id);
    }
    void lockSeats(UUID show,List<Integer> numbers) {
        Object[] args=new Object[numbers.size()+1]; args[0]=show;
        for(int index=0;index<numbers.size();index++) args[index+1]=numbers.get(index);
        String placeholders=String.join(",",Collections.nCopies(numbers.size(),"?"));
        var locked=db.jdbc().queryForList("SELECT seat_number FROM movie_show_seats WHERE show_id=? AND seat_number IN ("+
            placeholders+") ORDER BY seat_number FOR UPDATE",Integer.class,args);
        if(locked.size()!=numbers.size()) throw ApiException.missing();
    }
    BookingRow locked(UUID id) {
        BookingRow locator=row(id);
        lockSeats(locator.showId(),selected(id).stream().map(SelectedSeat::seatNumber).toList());
        return BookingStore.one(db.jdbc().query("SELECT * FROM movie_bookings WHERE id=? FOR UPDATE",BOOKING,id));
    }
    void release(UUID id) {
        // Every historical member row is locked first; never release a newer owner's seat.
        db.jdbc().update("UPDATE movie_show_seats SET active_booking_id=NULL WHERE active_booking_id=?",id);
    }
    void expireLocked(UUID id) {
        if(db.jdbc().update("UPDATE movie_bookings SET state='EXPIRED' WHERE id=? AND state IN ('HELD','PAYMENT_PENDING') AND expires_at<=clock_timestamp()",id)==1) {
            release(id); audit(id,"EXPIRED");
        }
    }
    boolean ownsAll(UUID id) {
        return Boolean.TRUE.equals(db.jdbc().queryForObject("""
            SELECT NOT EXISTS(SELECT 1 FROM movie_booking_seats h JOIN movie_show_seats s
              ON s.show_id=h.show_id AND s.seat_number=h.seat_number
              WHERE h.booking_id=? AND s.active_booking_id IS DISTINCT FROM h.booking_id)
            """,Boolean.class,id));
    }
    Booking view(UUID id,boolean replayed) {
        var b=row(id);
        return new Booking(b.id(),b.showId(),b.buyerId(),b.state(),b.expiresAt(),selected(id),b.amountMinor(),"INR",
            b.currentPaymentId()==null?null:payment(b.currentPaymentId()).payment(),replayed);
    }
    void audit(UUID id,String action) { db.jdbc().update("INSERT INTO movie_booking_audit(booking_id,action) VALUES (?,?)",id,action); }
}
