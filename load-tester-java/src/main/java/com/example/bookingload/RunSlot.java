package com.example.bookingload;

import org.springframework.stereotype.Component;

/** One finite run at a time across the generic and movie runners. */
@Component
class RunSlot {
    private String owner;
    synchronized boolean acquire(String run) {
        if (owner!=null) return false;
        owner=run;return true;
    }
    synchronized void release(String run) { if (run.equals(owner)) owner=null; }
    synchronized String owner() { return owner; }
}
