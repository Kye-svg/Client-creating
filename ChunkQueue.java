package dev.clicksclient.core;

import java.util.Iterator;
import java.util.LinkedHashSet;

/** FIFO of chunk keys that ignores duplicates already waiting. */
public final class ChunkQueue {
    private final LinkedHashSet<Long> set = new LinkedHashSet<>();

    public boolean add(long key) {
        return set.add(key);
    }

    /** Caller must check isEmpty() first. */
    public long poll() {
        Iterator<Long> it = set.iterator();
        long k = it.next();
        it.remove();
        return k;
    }

    public boolean remove(long key) {
        return set.remove(key);
    }

    public boolean isEmpty() {
        return set.isEmpty();
    }

    public int size() {
        return set.size();
    }

    public void clear() {
        set.clear();
    }
}
