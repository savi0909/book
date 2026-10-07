package com.example.bookingload;

import java.time.LocalDate;
import java.util.List;

/**
 * Finite movie advance-booking run. Each arrival is one simulated user journey:
 * browse a catalog multiplex's shows for {@code date}, open a seat map, then (by mix)
 * stop, hold and abandon, hold and pay, or pay and later cancel.
 */
public record MovieSpec(Integer rate, Integer seconds, Integer concurrency, LocalDate date, List<String> cities,
                        Integer bookPercent, Integer cancelPercent, Integer abandonPercent, Integer browsePercent,
                        Integer hotPercent, Integer ttlSeconds, Integer timeoutMs, Integer paymentWaitMs, Long seed) {
    static MovieSpec defaults() { return new MovieSpec(null,null,null,null,null,null,null,null,null,null,null,null,null,null); }

    MovieSpec normalized(boolean local,LocalDate today) {
        MovieSpec c=new MovieSpec(rate==null ? 5 : rate,seconds==null ? 60 : seconds,concurrency==null ? 16 : concurrency,
                date==null ? today.plusDays(1) : date,cities==null ? List.of() : List.copyOf(cities),
                bookPercent==null ? 65 : bookPercent,cancelPercent==null ? 10 : cancelPercent,
                abandonPercent==null ? 15 : abandonPercent,browsePercent==null ? 10 : browsePercent,
                hotPercent==null ? 60 : hotPercent,ttlSeconds==null ? 120 : ttlSeconds,timeoutMs==null ? 3500 : timeoutMs,
                paymentWaitMs==null ? 15000 : paymentWaitMs,seed==null ? 20261007L : seed);
        bound("rate",c.rate,1,local ? 20 : 50);
        bound("seconds",c.seconds,1,local ? 600 : 1800);
        bound("rate * seconds",c.total(),1,local ? 12000 : 90000);
        bound("concurrency",c.concurrency,1,local ? 16 : 64);
        for (int percent:List.of(c.bookPercent,c.cancelPercent,c.abandonPercent,c.browsePercent,c.hotPercent))
            bound("percent",percent,0,100);
        if (c.bookPercent+c.cancelPercent+c.abandonPercent+c.browsePercent!=100)
            throw new IllegalArgumentException("bookPercent + cancelPercent + abandonPercent + browsePercent must be 100");
        bound("ttlSeconds",c.ttlSeconds,30,600);
        bound("timeoutMs",c.timeoutMs,100,3500);
        bound("paymentWaitMs",c.paymentWaitMs,1000,30000);
        if (c.date.isBefore(today) || c.date.isAfter(today.plusDays(3)))
            throw new IllegalArgumentException("date must be within today..today+3 (the booking window)");
        if (c.cities.size()>100 || c.cities.stream().anyMatch(city -> city==null || city.isBlank() || city.length()>60))
            throw new IllegalArgumentException("cities must be at most 100 non-blank names");
        return c;
    }
    int total() { return Math.multiplyExact(rate,seconds); }
    private static void bound(String name,int value,int min,int max) {
        if (value<min || value>max) throw new IllegalArgumentException(name+" must be "+min+".."+max);
    }
}
