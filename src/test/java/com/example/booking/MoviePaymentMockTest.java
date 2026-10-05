package com.example.booking;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class MoviePaymentMockTest {
    @Test void planHasExact95PercentFirstCall45AdditionalAnd05FailureIntervals() {
        int first=0,retry=0,failed=0;
        for(int bucket=0;bucket<10000;bucket++) {
            if(MoviePaymentMock.outcome(bucket,1).equals("SUCCESS")) first++;
            else if(MoviePaymentMock.outcome(bucket,2).equals("SUCCESS")) retry++;
            else failed++;
        }
        assertThat(first).isEqualTo(9500);assertThat(retry).isEqualTo(450);assertThat(failed).isEqualTo(50);
    }
    @Test void samePaymentIdAlwaysUsesSamePlanWhileNewPaymentsHaveTheirOwnPlan() {
        UUID id=UUID.randomUUID();int bucket=MoviePaymentMock.bucket(id);
        assertThat(bucket).isBetween(0,9999);assertThat(MoviePaymentMock.bucket(id)).isEqualTo(bucket);
        assertThat(java.util.stream.IntStream.range(0,100).map(n->MoviePaymentMock.bucket(UUID.randomUUID())).distinct().count()).isGreaterThan(1);
    }
}
