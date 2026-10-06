package com.example.bookingload;

public record RunSpec(Mode mode, Integer rate, Integer seconds, Integer seats, Integer holdPercent,
                      Policy policy, Integer maxAttempts, Integer concurrency, Integer timeoutMs,
                      Integer deadlineMs, Integer ttlSeconds, Long seed) {
    public enum Mode { HOLD, AVAILABILITY, MIXED }
    public enum Policy { NONE, IMMEDIATE, JITTER }

    static RunSpec defaults() { return new RunSpec(null,null,null,null,null,null,null,null,null,null,null,null); }
    RunSpec normalized(boolean local) {
        RunSpec c = new RunSpec(mode == null ? Mode.HOLD : mode, rate == null ? 10 : rate,
                seconds == null ? 10 : seconds, seats == null ? 100 : seats,
                holdPercent == null ? 20 : holdPercent, policy == null ? Policy.NONE : policy,
                maxAttempts == null ? 1 : maxAttempts, concurrency == null ? 8 : concurrency,
                timeoutMs == null ? 3500 : timeoutMs, deadlineMs == null ? 10000 : deadlineMs,
                ttlSeconds == null ? 120 : ttlSeconds, seed == null ? 20261006L : seed);
        bound("rate",c.rate,1,local ? 10 : 100);
        bound("seconds",c.seconds,1,local ? 10 : 60);
        bound("rate * seconds",c.total(),1,local ? 100 : 200);
        bound("seats",c.seats,1,200);
        bound("holdPercent",c.holdPercent,0,100);
        bound("maxAttempts",c.maxAttempts,1,4);
        bound("concurrency",c.concurrency,1,local ? 8 : 32);
        bound("timeoutMs",c.timeoutMs,100,3500);
        bound("deadlineMs",c.deadlineMs,100,10000);
        bound("ttlSeconds",c.ttlSeconds,2,120);
        if (c.policy == Policy.NONE && c.maxAttempts != 1)
            throw new IllegalArgumentException("NONE requires exactly one attempt");
        return c;
    }
    int total() { return Math.multiplyExact(rate, seconds); }
    private static void bound(String name,int value,int min,int max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be " + min + ".." + max);
    }
}
