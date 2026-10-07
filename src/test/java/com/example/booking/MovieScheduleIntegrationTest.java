package com.example.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static com.example.booking.MovieModels.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"lab.maintenance-enabled=false","movie.payment-worker-enabled=false",
    "movie.payment-controls-enabled=true","movie.schedule-enabled=false"})
class MovieScheduleIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    @Autowired MovieScheduleMaintainer maintainer;
    @Autowired MovieCatalog catalog;
    @Autowired MovieBookingService bookings;
    @Autowired MoviePaymentService payments;
    @Autowired BookingStore store;
    @Autowired DataSource dataSource;

    LocalDate today() { return store.jdbc().queryForObject("SELECT (clock_timestamp() AT TIME ZONE 'Asia/Kolkata')::date",LocalDate.class); }
    /** Each test schedules its own catalog site so tests never share screen-days. */
    UUID site(int index) {
        return store.jdbc().queryForObject("SELECT multiplex_id FROM movie_catalog_sites ORDER BY multiplex_id OFFSET ? LIMIT 1",UUID.class,index);
    }
    int count(String sql,Object... args) { return store.jdbc().queryForObject(sql,Integer.class,args); }
    Booking hold(UUID show,List<Integer> seats) {
        return bookings.hold(new HoldRequest(show,seats,"buyer-"+UUID.randomUUID(),120),"hold-"+UUID.randomUUID());
    }
    UUID firstShow(UUID multiplex,LocalDate day) {
        return catalog.shows(multiplex,day,1,0).getFirst().id();
    }

    @Test void permanentCatalogHonoursScreenSeatAndClassRules() {
        assertThat(count("SELECT count(*) FROM movie_catalog_sites")).isEqualTo(303);
        assertThat(count("SELECT count(DISTINCT city) FROM movie_catalog_sites")).isEqualTo(77);
        assertThat(count("""
            SELECT count(*) FROM movie_catalog_sites cs JOIN movie_multiplexes mx ON mx.id=cs.multiplex_id
            WHERE mx.screen_count<5 OR mx.screen_count<>(SELECT count(*) FROM movie_screens s WHERE s.multiplex_id=mx.id)
            """)).isZero();
        assertThat(count("""
            SELECT count(*) FROM movie_screens s JOIN movie_catalog_sites cs ON cs.multiplex_id=s.multiplex_id
            WHERE s.seat_count NOT BETWEEN 200 AND 500
               OR (SELECT count(*) FROM movie_screen_categories c WHERE c.screen_id=s.id) NOT BETWEEN 2 AND 3
               OR (SELECT sum(seat_count) FROM movie_screen_categories c WHERE c.screen_id=s.id)<>s.seat_count
               OR (SELECT sum(percentage) FROM movie_screen_categories c WHERE c.screen_id=s.id)<>100
            """)).isZero();
        assertThat(count("SELECT count(*) FROM movie_now_showing")).isEqualTo(20);
        assertThat(count("SELECT count(*) FROM movie_now_showing WHERE blockbuster")).isEqualTo(3);
    }

    @Test void browseListsOnlyCatalogSitesByCityAndTheSlateBlockbustersFirst() {
        catalog.createMultiplex(new MultiplexRequest("Demo "+UUID.randomUUID(),"Asia/Kolkata",
            java.util.stream.IntStream.rangeClosed(1,5).mapToObj(n->new ScreenRequest("Screen "+n,200,null)).toList()));
        var all=catalog.catalogMultiplexes(null);
        assertThat(all).hasSize(303).allSatisfy(m->assertThat(m.screenCount()).isBetween(5,100));
        var pune=catalog.catalogMultiplexes("Pune");
        assertThat(pune).isNotEmpty().allSatisfy(m->assertThat(m.city()).isEqualTo("Pune"));
        assertThat(catalog.catalogMultiplexes("Atlantis")).isEmpty();
        var slate=catalog.nowShowing();
        assertThat(slate).hasSize(20);
        assertThat(slate.subList(0,3)).allSatisfy(m->assertThat(m.blockbuster()).isTrue());
    }

    @Test void fillIsIdempotentAndBuildsNonOverlappingPricedShowsInsideOpeningHours() {
        UUID multiplex=site(0);var day=today().plusDays(1);
        var first=maintainer.fill(day,List.of(multiplex));
        int screens=count("SELECT count(*) FROM movie_screens WHERE multiplex_id=?",multiplex);
        assertThat(first.shows()).isGreaterThanOrEqualTo(screens*4);
        assertThat(maintainer.fill(day,List.of(multiplex))).isEqualTo(new MovieScheduleMaintainer.Fill(0,0));
        var shows=catalog.shows(multiplex,day,500,0);
        assertThat(shows).hasSize(first.shows());
        assertThat(first.seats()).isEqualTo(shows.stream().mapToInt(Show::seatCount).sum());
        for(var show:shows) {
            var local=show.startsAt().atZone(IST).toLocalTime();
            assertThat(local).isBetween(LocalTime.of(9,0),LocalTime.of(23,30));
            assertThat(show.occupiedUntil()).isEqualTo(show.endsAt().plusSeconds(20*60));
        }
        assertThat(count("""
            SELECT count(*) FROM movie_shows a JOIN movie_shows b ON a.screen_id=b.screen_id AND a.id<>b.id
            JOIN movie_screens s ON s.id=a.screen_id
            WHERE s.multiplex_id=? AND a.starts_at<b.occupied_until AND b.starts_at<a.occupied_until
            """,multiplex)).isZero();
        assertThat(count("""
            SELECT count(*) FROM movie_show_seats ss JOIN movie_shows sh ON sh.id=ss.show_id
            JOIN movie_screens s ON s.id=sh.screen_id JOIN movie_catalog_sites cs ON cs.multiplex_id=s.multiplex_id
            JOIN movie_catalog_prices p ON p.price_tier=cs.price_tier AND p.category=ss.category
            WHERE s.multiplex_id=? AND ss.price_minor<>p.price_minor
            """,multiplex)).isZero();
        assertThat(count("""
            SELECT count(*) FROM movie_shows sh JOIN movie_screens s ON s.id=sh.screen_id
            WHERE s.multiplex_id=? AND sh.movie_id NOT IN (SELECT movie_id FROM movie_now_showing)
            """,multiplex)).isZero();
    }

    @Test void holdsOpenThreeLocalDaysAheadAndNotBeyond() {
        UUID multiplex=site(1);
        maintainer.fill(today().plusDays(3),List.of(multiplex));
        maintainer.fill(today().plusDays(4),List.of(multiplex));
        assertThat(hold(firstShow(multiplex,today().plusDays(3)),List.of(1,2)).state()).isEqualTo("HELD");
        UUID tooEarly=firstShow(multiplex,today().plusDays(4));
        assertThatThrownBy(()->hold(tooEarly,List.of(1))).isInstanceOf(ApiException.class)
            .satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("SHOW_NOT_YET_OPEN"));
        assertThat(count("SELECT count(*) FROM movie_bookings WHERE show_id=?",tooEarly)).isZero();
    }

    @Test void purgeRemovesPastDaysWithEveryDependentRowButKeepsTodayAndOpenPayments() {
        UUID multiplex=site(2);var tomorrow=today().plusDays(1);
        maintainer.fill(tomorrow,List.of(multiplex));
        var shows=catalog.shows(multiplex,tomorrow,500,0);
        UUID settled=shows.get(0).id(),open=shows.get(1).id(),kept=shows.get(2).id();
        var confirmed=hold(settled,List.of(1,2,3));
        var paid=payments.checkout(confirmed.id(),"pay-"+UUID.randomUUID(),new CheckoutRequest(0,0));
        assertThat(payments.reconcile(paid.payment().id()).state()).isEqualTo("CONFIRMED");
        hold(settled,List.of(10));
        var pending=hold(open,List.of(5));
        payments.checkout(pending.id(),"pay-"+UUID.randomUUID(),new CheckoutRequest(0,0));
        // Move two shows to yesterday; the third stays in the window.
        for(UUID show:List.of(settled,open))
            store.jdbc().update("""
                UPDATE movie_shows SET starts_at=starts_at-interval '2 days',ends_at=ends_at-interval '2 days',
                  occupied_until=occupied_until-interval '2 days' WHERE id=?
                """,show);
        int seats=count("SELECT count(*) FROM movie_show_seats WHERE show_id=?",settled);

        var purged=maintainer.purgePast();

        assertThat(purged.shows()).isGreaterThanOrEqualTo(1);
        assertThat(purged.seats()).isGreaterThanOrEqualTo(seats);
        assertThat(purged.bookings()).isGreaterThanOrEqualTo(2);
        assertThat(purged.payments()).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT count(*) FROM movie_shows WHERE id=?",settled)).isZero();
        assertThat(count("SELECT count(*) FROM movie_show_seats WHERE show_id=?",settled)).isZero();
        assertThat(count("SELECT count(*) FROM movie_bookings WHERE id=?",confirmed.id())).isZero();
        assertThat(count("SELECT count(*) FROM movie_booking_seats WHERE booking_id=?",confirmed.id())).isZero();
        assertThat(count("SELECT count(*) FROM movie_booking_audit WHERE booking_id=?",confirmed.id())).isZero();
        assertThat(count("SELECT count(*) FROM movie_payments WHERE id=?",paid.payment().id())).isZero();
        assertThat(count("SELECT count(*) FROM movie_provider_receipts WHERE payment_id=?",paid.payment().id())).isZero();
        assertThat(count("SELECT count(*) FROM movie_shows WHERE id IN (?,?)",open,kept)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM movie_purge_log WHERE shows>0")).isPositive();
        assertThat(maintainer.purgePast().shows()).isZero();
    }

    @Test void replicaWithoutTheMaintainerLockSkipsWork() throws Exception {
        UUID multiplex=site(3);
        try(var other=dataSource.getConnection()) {
            other.setAutoCommit(false);
            try(var statement=other.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1,MovieScheduleMaintainer.LOCK_KEY);statement.execute();
            }
            assertThat(maintainer.fill(today().plusDays(2),List.of(multiplex))).isNull();
            other.rollback();
        }
        assertThat(maintainer.fill(today().plusDays(2),List.of(multiplex)).shows()).isPositive();
    }
}
