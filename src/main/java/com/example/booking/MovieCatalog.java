package com.example.booking;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.RowMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.example.booking.MovieModels.*;

@Service
public class MovieCatalog {
    private final BookingStore store;
    public MovieCatalog(BookingStore store) { this.store=store; }
    static final String SHOW_SELECT="""
        SELECT sh.*,s.multiplex_id,s.name AS screen_name,s.seat_count,m.title,mx.zone_id
        FROM movie_shows sh JOIN movie_screens s ON s.id=sh.screen_id
        JOIN movie_multiplexes mx ON mx.id=s.multiplex_id JOIN movies m ON m.id=sh.movie_id
        """;
    static final RowMapper<Show> SHOW=(rs,n)->new Show(rs.getObject("id",UUID.class),rs.getObject("movie_id",UUID.class),
        rs.getObject("screen_id",UUID.class),rs.getObject("multiplex_id",UUID.class),rs.getString("title"),
        rs.getString("screen_name"),rs.getString("zone_id"),BookingStore.instant(rs,"starts_at"),
        BookingStore.instant(rs,"ends_at"),BookingStore.instant(rs,"occupied_until"),rs.getInt("seat_count"));
    public Multiplex createMultiplex(MultiplexRequest r) {
        zone(r.zoneId());
        if(r.screens().stream().map(ScreenRequest::name).distinct().count()!=r.screens().size())
            throw new ApiException(400,"DUPLICATE_SCREEN","Screen names must be unique within a multiplex");
        for(var screen:r.screens()) validateLayout(screen.categories());
        return store.tx(()-> {
            UUID id=UUID.randomUUID();
            store.jdbc().update("INSERT INTO movie_multiplexes(id,name,zone_id,screen_count) VALUES (?,?,?,?)",
                id,r.name(),r.zoneId(),r.screens().size());
            for(var screen:r.screens()) {
                UUID screenId=UUID.randomUUID();
                store.jdbc().update("INSERT INTO movie_screens(id,multiplex_id,name,seat_count) VALUES (?,?,?,?)",
                    screenId,id,screen.name(),screen.seatCount());
                var categories=screen.categories().keySet().stream().sorted().toList();
                int allocated=0;
                for(int index=0;index<categories.size();index++) {
                    var category=categories.get(index);
                    int count=index==categories.size()-1 ? screen.seatCount()-allocated : screen.seatCount()*screen.categories().get(category)/100;
                    allocated+=count;
                    store.jdbc().update("INSERT INTO movie_screen_categories VALUES (?,?,?,?)",screenId,category.name(),screen.categories().get(category),count);
                }
            }
            return multiplex(id);
        });
    }
    static void validateLayout(Map<Category,Integer> layout) {
        if(layout.size()<2 || layout.size()>3 || layout.values().stream().anyMatch(v->v==null || v<1 || v>99)
                || layout.values().stream().mapToInt(Integer::intValue).sum()!=100)
            throw new ApiException(400,"INVALID_CATEGORIES","Use two or three categories with positive percentages adding to 100");
    }
    static ZoneId zone(String name) {
        try { return ZoneId.of(name); } catch(DateTimeException e) { throw new ApiException(400,"INVALID_TIME_ZONE","Use an IANA zone such as Asia/Kolkata"); }
    }
    public Multiplex multiplex(UUID id) {
        var row=BookingStore.one(store.jdbc().query("SELECT name,zone_id FROM movie_multiplexes WHERE id=?",
            (rs,n)->List.of(rs.getString("name"),rs.getString("zone_id")),id));
        var screens=store.jdbc().query("SELECT * FROM movie_screens WHERE multiplex_id=? ORDER BY name,id",
            (rs,n)->new Screen(rs.getObject("id",UUID.class),rs.getString("name"),rs.getInt("seat_count"),
                categorySeats(rs.getObject("id",UUID.class))),id);
        return new Multiplex(id,row.get(0),row.get(1),screens);
    }
    Map<Category,Integer> categorySeats(UUID screen) {
        Map<Category,Integer> result=new EnumMap<>(Category.class);
        store.jdbc().query("SELECT category,seat_count FROM movie_screen_categories WHERE screen_id=? ORDER BY category",
            rs->{result.put(Category.valueOf(rs.getString("category")),rs.getInt("seat_count"));},screen);
        return result;
    }
    /** Permanent catalog sites only (V7); demo multiplexes are reachable by id. */
    public List<MultiplexSummary> catalogMultiplexes(String city) {
        return store.jdbc().query("""
            SELECT mx.id,mx.name,cs.brand,cs.city,cs.state,mx.screen_count
            FROM movie_catalog_sites cs JOIN movie_multiplexes mx ON mx.id=cs.multiplex_id
            WHERE ?::text IS NULL OR cs.city=? ORDER BY cs.city,mx.name
            """,(rs,n)->new MultiplexSummary(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("brand"),
                rs.getString("city"),rs.getString("state"),rs.getInt("screen_count")),city,city);
    }
    public List<NowShowing> nowShowing() {
        return store.jdbc().query("""
            SELECT m.id,m.title,m.language,m.duration_minutes,n.blockbuster
            FROM movie_now_showing n JOIN movies m ON m.id=n.movie_id ORDER BY n.weight DESC,m.title
            """,(rs,n)->new NowShowing(rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("language"),
                rs.getInt("duration_minutes"),rs.getBoolean("blockbuster")));
    }
    public Movie createMovie(MovieRequest r) {
        UUID id=UUID.randomUUID();
        store.jdbc().update("INSERT INTO movies VALUES (?,?,?,?)",id,r.title(),r.language(),r.durationMinutes());
        return new Movie(id,r.title(),r.language(),r.durationMinutes());
    }
    public Show createShow(ShowRequest r) {
        return store.tx(()-> {
            BookingStore.one(store.jdbc().queryForList("SELECT id FROM movie_screens WHERE id=? FOR UPDATE",UUID.class,r.screenId()));
            int duration=BookingStore.one(store.jdbc().queryForList("SELECT duration_minutes FROM movies WHERE id=?",Integer.class,r.movieId()));
            var layout=categorySeats(r.screenId());
            if(!r.pricesMinor().keySet().equals(layout.keySet()))
                throw new ApiException(400,"PRICE_CATEGORIES_MISMATCH","Supply a price for every configured screen category, and no others");
            Instant end=r.startsAt().plusSeconds(duration*60L),occupied=end.plusSeconds(r.turnaroundMinutes()*60L);
            Boolean future=store.jdbc().queryForObject("SELECT ?::timestamptz > clock_timestamp()",Boolean.class,Timestamp.from(r.startsAt()));
            if(!Boolean.TRUE.equals(future)) throw ApiException.conflict("SHOW_NOT_FUTURE","New shows must start in the future");
            if(Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM movie_shows WHERE screen_id=? AND starts_at<? AND occupied_until>?)",
                    Boolean.class,r.screenId(),Timestamp.from(occupied),Timestamp.from(r.startsAt()))))
                throw ApiException.conflict("SCREEN_SCHEDULE_OVERLAP","Screen is occupied by a show or its turnaround interval");
            UUID id=UUID.randomUUID();
            store.jdbc().update("INSERT INTO movie_shows VALUES (?,?,?,?,?,?)",id,r.movieId(),r.screenId(),Timestamp.from(r.startsAt()),Timestamp.from(end),Timestamp.from(occupied));
            int first=1;
            for(var category:layout.keySet()) {
                int last=first+layout.get(category)-1;
                store.jdbc().update("INSERT INTO movie_show_seats(show_id,seat_number,category,price_minor) SELECT ?,n,?,? FROM generate_series(?,?) n",
                    id,category.name(),r.pricesMinor().get(category),first,last);
                first=last+1;
            }
            return show(id);
        });
    }
    public Show show(UUID id) { return BookingStore.one(store.jdbc().query(SHOW_SELECT+" WHERE sh.id=?",SHOW,id)); }
    public List<Show> shows(UUID multiplex,LocalDate date,int limit,int offset) {
        var row=BookingStore.one(store.jdbc().queryForList("SELECT zone_id FROM movie_multiplexes WHERE id=?",String.class,multiplex));
        ZoneId zone=zone(row);
        return store.jdbc().query(SHOW_SELECT+" WHERE s.multiplex_id=? AND sh.starts_at>=? AND sh.starts_at<? ORDER BY sh.starts_at,sh.id LIMIT ? OFFSET ?",
            SHOW,multiplex,Timestamp.from(date.atStartOfDay(zone).toInstant()),Timestamp.from(date.plusDays(1).atStartOfDay(zone).toInstant()),limit,offset);
    }
    public List<Seat> seats(UUID show,int limit,int offset) {
        show(show);
        return store.jdbc().query("""
            SELECT s.seat_number,s.category,s.price_minor,
              CASE WHEN b.state='CONFIRMED' THEN 'BOOKED'
              WHEN b.state IN ('HELD','PAYMENT_PENDING') AND b.expires_at>statement_timestamp() THEN 'HELD'
              ELSE 'AVAILABLE' END AS availability
            FROM movie_show_seats s LEFT JOIN movie_bookings b ON b.id=s.active_booking_id
            WHERE s.show_id=? ORDER BY s.seat_number LIMIT ? OFFSET ?
            """,(rs,n)->new Seat(rs.getInt("seat_number"),Category.valueOf(rs.getString("category")),rs.getInt("price_minor"),rs.getString("availability")),show,limit,offset);
    }
}
