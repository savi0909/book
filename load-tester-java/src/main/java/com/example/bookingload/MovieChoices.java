package com.example.bookingload;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;

/** Pure, seeded user choices so journeys are reproducible and unit-testable. */
final class MovieChoices {
    enum Kind { BOOK, CANCEL, ABANDON, BROWSE }
    static final ZoneId IST=ZoneId.of("Asia/Kolkata");
    // Group sizes 1..10; mostly couples and small families.
    private static final int[] GROUP_WEIGHTS={8,32,18,20,8,6,3,2,1,2};
    // Preferred class order weights: B (middle) 50, C (front) 30, A (premium) 20.
    private static final Map<String,Integer> CLASS_WEIGHTS=Map.of("B",50,"C",30,"A",20);
    private MovieChoices() {}

    static Kind kind(MovieSpec spec,Random random) {
        int roll=random.nextInt(100);
        if ((roll-=spec.bookPercent())<0) return Kind.BOOK;
        if ((roll-=spec.cancelPercent())<0) return Kind.CANCEL;
        if ((roll-=spec.abandonPercent())<0) return Kind.ABANDON;
        return Kind.BROWSE;
    }
    static int groupSize(Random random) {
        int roll=random.nextInt(100);
        for (int i=0;i<GROUP_WEIGHTS.length;i++) if ((roll-=GROUP_WEIGHTS[i])<0) return i+1;
        return 2;
    }

    /**
     * Hot users want a blockbuster in the 17:00-22:00 local evening band, falling back
     * to any blockbuster and then any show. Shows starting within 10 minutes are skipped.
     */
    static JsonNode show(JsonNode shows,Set<String> blockbusters,boolean hot,Instant now,Random random) {
        List<JsonNode> open=new ArrayList<>();
        for (JsonNode show:shows) if (Instant.parse(show.path("startsAt").asText()).isAfter(now.plusSeconds(600))) open.add(show);
        if (open.isEmpty()) return null;
        if (hot) {
            List<JsonNode> blockbuster=open.stream().filter(s -> blockbusters.contains(s.path("title").asText())).toList();
            List<JsonNode> evening=blockbuster.stream().filter(s -> {
                int hour=Instant.parse(s.path("startsAt").asText()).atZone(IST).getHour();
                return hour>=17 && hour<22;
            }).toList();
            if (!evening.isEmpty()) return evening.get(random.nextInt(evening.size()));
            if (!blockbuster.isEmpty()) return blockbuster.get(random.nextInt(blockbuster.size()));
        }
        return open.get(random.nextInt(open.size()));
    }

    /**
     * Picks {@code size} adjacent AVAILABLE seats inside one class, trying a weighted
     * preferred class first; falls back to any seats of one class, then null (sold out).
     */
    static List<Integer> seats(JsonNode seatMap,int size,Random random) {
        Map<String,List<Integer>> free=new TreeMap<>();
        for (JsonNode seat:seatMap) if (seat.path("availability").asText().equals("AVAILABLE"))
            free.computeIfAbsent(seat.path("category").asText(),c -> new ArrayList<>()).add(seat.path("seatNumber").asInt());
        if (free.isEmpty()) return null;
        List<String> order=preferenceOrder(free.keySet(),random);
        for (String category:order) {
            List<Integer> numbers=free.get(category);Collections.sort(numbers);
            List<Integer> starts=new ArrayList<>();
            for (int i=0;i+size<=numbers.size();i++)
                if (numbers.get(i+size-1)-numbers.get(i)==size-1) starts.add(i);
            if (!starts.isEmpty()) {
                int start=starts.get(random.nextInt(starts.size()));
                return List.copyOf(numbers.subList(start,start+size));
            }
        }
        for (String category:order) if (free.get(category).size()>=size) {
            List<Integer> numbers=new ArrayList<>(free.get(category));Collections.shuffle(numbers,random);
            return numbers.subList(0,size).stream().sorted().toList();
        }
        return null;
    }
    private static List<String> preferenceOrder(Set<String> categories,Random random) {
        List<String> remaining=new ArrayList<>(categories),order=new ArrayList<>();
        while (!remaining.isEmpty()) {
            int total=remaining.stream().mapToInt(c -> CLASS_WEIGHTS.getOrDefault(c,10)).sum(),roll=random.nextInt(total);
            for (Iterator<String> it=remaining.iterator();it.hasNext();) {
                String category=it.next();
                if ((roll-=CLASS_WEIGHTS.getOrDefault(category,10))<0) { order.add(category);it.remove();break; }
            }
        }
        return order;
    }
}
