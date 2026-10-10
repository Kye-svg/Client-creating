package dev.clicksclient.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Flagged chunks, kept separately per world/dimension so markers survive dimension hops. */
public final class FlagStore {
    private final Map<String, LinkedHashMap<Long, Flag>> worlds = new HashMap<>();
    private LinkedHashMap<Long, Flag> cur = new LinkedHashMap<>();
    private String curName = "";

    public void selectWorld(String name) {
        if (name.equals(curName)) return;
        curName = name;
        cur = worlds.computeIfAbsent(name, k -> new LinkedHashMap<>());
        if (worlds.size() > 8) {
            for (Iterator<String> it = worlds.keySet().iterator(); it.hasNext() && worlds.size() > 8; ) {
                String k = it.next();
                if (!k.equals(curName)) it.remove();
            }
        }
    }

    /** Creates or refreshes a flag. Returns true when the chunk was not flagged before. */
    public boolean flag(int chunkX, int chunkZ, int ax, int ay, int az, float severity, String label, long nowMs) {
        long key = Depth.chunkKey(chunkX, chunkZ);
        Flag f = cur.get(key);
        boolean isNew = f == null;
        if (isNew) {
            f = new Flag(chunkX, chunkZ);
            f.firstSeen = nowMs;
            f.severity = severity;
            f.ax = ax;
            f.ay = ay;
            f.az = az;
            f.label = label;
            cur.put(key, f);
        } else if (severity >= f.severity) {
            f.severity = severity;
            f.ax = ax;
            f.ay = ay;
            f.az = az;
            f.label = label;
        }
        f.lastSeen = nowMs;
        f.hits++;
        return isNew;
    }

    public Flag get(long key) { return cur.get(key); }

    public boolean remove(long key) { return cur.remove(key) != null; }

    public Collection<Flag> values() { return cur.values(); }

    public int size() { return cur.size(); }

    public boolean isEmpty() { return cur.isEmpty(); }

    public void clear() { cur.clear(); }

    public void clearAll() {
        worlds.clear();
        cur = new LinkedHashMap<>();
        curName = "";
    }

    public void clearTests() {
        cur.values().removeIf(f -> f.test);
    }

    /** Test flags only live for a short while so they never linger. */
    public void pruneTests(long nowMs, long maxAgeMs) {
        cur.values().removeIf(f -> f.test && nowMs - f.firstSeen > maxAgeMs);
    }

    /** Drops flags older than maxAgeMs (0 = never) and trims to maxEntries, oldest first. */
    public void prune(long nowMs, long maxAgeMs, int maxEntries) {
        if (maxAgeMs > 0) {
            cur.values().removeIf(f -> nowMs - f.lastSeen > maxAgeMs);
        }
        if (cur.size() > maxEntries) {
            ArrayList<Long> keys = new ArrayList<>(cur.keySet());
            for (int i = 0; i < keys.size() - maxEntries; i++) cur.remove(keys.get(i));
        }
    }
}
