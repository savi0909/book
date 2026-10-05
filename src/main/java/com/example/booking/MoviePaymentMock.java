package com.example.booking;

import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Durable local mock, with immutable per-logical-call receipts; no money is charged. */
@Component
public class MoviePaymentMock {
    private final BookingStore store;
    public MoviePaymentMock(BookingStore store) { this.store=store; }
    static int bucket(UUID payment) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(payment.toString().getBytes(StandardCharsets.US_ASCII));
            return (int)Math.floorMod(ByteBuffer.wrap(digest).getLong(),10000L);
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String outcome(int bucket,int step) {
        if(bucket<9500) return "SUCCESS";
        if(step==1) return "RETRYABLE_FAILURE";
        return bucket<9950 ? "SUCCESS":"FAILURE";
    }
    String accept(UUID payment,int bucket,int step) {
        // Separate commit from booking application permits replay after a lost response/crash.
        return store.tx(()-> {
            store.jdbc().update("INSERT INTO movie_provider_receipts(payment_id,provider_step,outcome) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                payment,step,outcome(bucket,step));
            return store.jdbc().queryForObject("SELECT outcome FROM movie_provider_receipts WHERE payment_id=? AND provider_step=?",String.class,payment,step);
        });
    }
}
