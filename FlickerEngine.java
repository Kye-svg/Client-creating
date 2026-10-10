package dev.clicksclient.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Detects things that keep switching: block states that flip and light sections that keep updating. */
public final class FlickerEngine {

    public static final class Params {
        public int windowSec = 10;
        public int minToggles = 6;
        public int minActive = 3;
        public int minLight = 8;
        public int minLightActive = 4;
    }

    public static final class Hit {
        public final boolean fromLight;
        public final long chunkKey, pos;
        public final int index, count, active;

        Hit(boolean fromLight, long chunkKey, long pos, int index, int count, int active) {
            this.fromLight = fromLight;
            this.chunkKey = chunkKey;
            this.pos = pos;
            this.index = index;
            this.count = count;
            this.active = active;
        }
    }

    private static final int MAX_TRACKED = 50_000;

    private final Map<Long, WindowCounter> flips = new HashMap<>();
    private final Map<Long, WindowCounter[]> light = new HashMap<>();

    /** A tracked block changed state. Returns a hit once it has toggled enough, over enough separate seconds. */
    public Hit onFlip(long posKey, long chunkKey, int nowSec, Params p) {
        if (flips.size() > MAX_TRACKED) flips.clear();
        WindowCounter c = flips.computeIfAbsent(posKey, k -> new WindowCounter());
        c.add(nowSec);
        int total = c.total(nowSec, p.windowSec);
        if (total < p.minToggles) return null;
        int active = c.activeSeconds(nowSec, p.windowSec);
        if (active < p.minActive) return null;
        return new Hit(false, chunkKey, posKey, -1, total, active);
    }

    /** A light-update packet touched a section's block light. */
    public Hit onLight(long chunkKey, int index, int size, int nowSec, Params p) {
        if (light.size() > MAX_TRACKED) light.clear();
        WindowCounter[] arr = light.get(chunkKey);
        if (arr == null || arr.length != size) {
            arr = new WindowCounter[size];
            light.put(chunkKey, arr);
        }
        if (index < 0 || index >= size) return null;
        if (arr[index] == null) arr[index] = new WindowCounter();
        WindowCounter c = arr[index];
        c.add(nowSec);
        int total = c.total(nowSec, p.windowSec);
        if (total < p.minLight) return null;
        int active = c.activeSeconds(nowSec, p.windowSec);
        if (active < p.minLightActive) return null;
        return new Hit(true, chunkKey, 0L, index, total, active);
    }

    public void dropChunk(long chunkKey) {
        light.remove(chunkKey);
    }

    public void sweep(int nowSec, int windowSec) {
        for (Iterator<WindowCounter> it = flips.values().iterator(); it.hasNext(); ) {
            if (it.next().isStale(nowSec, windowSec)) it.remove();
        }
        for (Iterator<WindowCounter[]> it = light.values().iterator(); it.hasNext(); ) {
            boolean anyLive = false;
            for (WindowCounter c : it.next()) {
                if (c != null && !c.isStale(nowSec, windowSec)) {
                    anyLive = true;
                    break;
                }
            }
            if (!anyLive) it.remove();
        }
    }

    public void clear() {
        flips.clear();
        light.clear();
    }

    public int trackedBlocks() { return flips.size(); }

    public int trackedChunks() { return light.size(); }
}
