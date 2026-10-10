package dev.clicksclient.core;

/**
 * Sliding window of per-second event counters. Constant memory no matter how fast events arrive,
 * which matters for fast redstone clocks.
 */
public final class WindowCounter {
    public static final int SLOTS = 64; // supports windows up to 64 seconds

    private final int[] second = new int[SLOTS];
    private final int[] count = new int[SLOTS];

    public void add(int nowSec) {
        int i = nowSec & (SLOTS - 1);
        if (second[i] != nowSec) {
            second[i] = nowSec;
            count[i] = 0;
        }
        count[i]++;
    }

    /** Events seen in the last windowSec seconds (current second included). */
    public int total(int nowSec, int windowSec) {
        int sum = 0;
        for (int i = 0; i < SLOTS; i++) {
            int age = nowSec - second[i];
            if (age >= 0 && age < windowSec) sum += count[i];
        }
        return sum;
    }

    /** Distinct seconds inside the window that saw at least one event. */
    public int activeSeconds(int nowSec, int windowSec) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            int age = nowSec - second[i];
            if (age >= 0 && age < windowSec && count[i] > 0) n++;
        }
        return n;
    }

    public boolean isStale(int nowSec, int windowSec) {
        return total(nowSec, windowSec) == 0;
    }
}
