package dev.clicksclient.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side model of the light data the server has sent, kept only for sections at or below Y 15
 * (everything of interest is below Y 0, one section above is kept so neighbour lookups work).
 * Pure Java: no Minecraft types.
 */
public final class LightWorld {

    /** Per-chunk state. Arrays are indexed by light-packet section index (0 .. sectionCount + 1). */
    public static final class Chunk {
        public final int x, z;
        public final long key;
        public int bottomSectionY, sectionCount;
        public byte[] sky = new byte[0];
        public byte[] blockStatus = new byte[0];
        public byte[][] block = new byte[0][];
        public long loadedAtMs;

        Chunk(int x, int z) {
            this.x = x;
            this.z = z;
            this.key = Depth.chunkKey(x, z);
        }

        void alloc(int bottomSectionY, int sectionCount) {
            this.bottomSectionY = bottomSectionY;
            this.sectionCount = sectionCount;
            int n = sectionCount + 2;
            sky = new byte[n];
            blockStatus = new byte[n];
            block = new byte[n][];
        }

        /**
         * Number of sections at or below ceilingY whose light arrays show they have been touched:
         * a sky array that was sent but is entirely zero (pristine sections come through the "empty"
         * mask instead), and optionally any allocated block-light array.
         */
        public int touchedSections(int ceilingY, boolean includeBlockArrays) {
            int n = 0;
            for (int i = 1; i <= sectionCount; i++) {
                int secY = Depth.sectionY(bottomSectionY, i);
                if (!Depth.sectionTouchesBelow(secY, ceilingY)) continue;
                boolean touched = sky[i] == LightCodec.ZEROED;
                if (!touched && includeBlockArrays) {
                    byte st = blockStatus[i];
                    touched = st == LightCodec.ZEROED || st == LightCodec.NORMAL || st == LightCodec.UNIFORM;
                }
                if (touched) n++;
            }
            return n;
        }

        /** Section indexes at or below ceilingY that the event reported as changed (update packets only). */
        public int[] changedIndexes(LightEvent e, int ceilingY, boolean includeSky, boolean includeBlock) {
            int[] tmp = new int[sectionCount + 2];
            int n = 0;
            for (int i = 1; i <= sectionCount; i++) {
                int secY = Depth.sectionY(bottomSectionY, i);
                if (!Depth.sectionTouchesBelow(secY, ceilingY)) continue;
                boolean hit = false;
                if (includeSky && i < e.sky.length && e.sky[i] != LightCodec.ABSENT) hit = true;
                if (includeBlock && i < e.block.length && e.block[i] != null) hit = true;
                if (hit) tmp[n++] = i;
            }
            return java.util.Arrays.copyOf(tmp, n);
        }

        /** Block light at chunk-local x/z and absolute y. 0 outside the world, -1 when not known yet. */
        public int levelAt(int lx, int wy, int lz) {
            int secY = wy >> 4;
            int idx = secY - bottomSectionY + 1;
            if (idx < 0 || idx > sectionCount + 1) return 0;
            if (!Depth.retainSection(secY)) return 0;
            byte[] a = block[idx];
            if (a == null) return -1;
            return LightCodec.nibble(a, lx, wy & 15, lz);
        }
    }

    public interface Listener {
        /** Called after an event has been merged into the chunk state. */
        default void onLight(Chunk c, LightEvent e) {}

        default void onUnload(long key) {}

        default void onReset() {}
    }

    private final Map<Long, Chunk> chunks = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public int listenerCount() {
        return listeners.size();
    }

    public Chunk get(int cx, int cz) {
        return chunks.get(Depth.chunkKey(cx, cz));
    }

    public Chunk get(long key) {
        return chunks.get(key);
    }

    public Collection<Chunk> all() {
        return chunks.values();
    }

    public int size() {
        return chunks.size();
    }

    public void apply(LightEvent e, int bottomSectionY, int sectionCount) {
        long key = Depth.chunkKey(e.chunkX, e.chunkZ);
        Chunk c = chunks.get(key);
        if (c == null || c.bottomSectionY != bottomSectionY || c.sectionCount != sectionCount) {
            c = new Chunk(e.chunkX, e.chunkZ);
            c.alloc(bottomSectionY, sectionCount);
            c.loadedAtMs = e.nowMs;
            chunks.put(key, c);
        } else if (e.full) {
            c.loadedAtMs = e.nowMs;
        }

        int size = sectionCount + 2;
        for (int i = 0; i < size; i++) {
            int secY = Depth.sectionY(bottomSectionY, i);
            if (!Depth.retainSection(secY)) continue;
            boolean inSky = i < e.sky.length && e.sky[i] != LightCodec.ABSENT;
            boolean inBlock = i < e.block.length && e.block[i] != null;
            if (inSky) c.sky[i] = e.sky[i];
            else if (e.full) c.sky[i] = LightCodec.ABSENT;
            if (inBlock) {
                c.block[i] = e.block[i];
                c.blockStatus[i] = e.blockStatus[i];
            } else if (e.full) {
                c.block[i] = LightCodec.ZERO; // a full packet that does not mention a section means it has no light
                c.blockStatus[i] = LightCodec.ABSENT;
            }
        }

        if (!listeners.isEmpty()) {
            for (Listener l : new ArrayList<>(listeners)) l.onLight(c, e);
        }
    }

    public void unload(long key) {
        chunks.remove(key);
        for (Listener l : new ArrayList<>(listeners)) l.onUnload(key);
    }

    public void clear() {
        chunks.clear();
        for (Listener l : new ArrayList<>(listeners)) l.onReset();
    }

    /** Block light at a world position: 0 outside the world, -1 when the chunk or section is not known. */
    public int levelAt(int wx, int wy, int wz) {
        Chunk c = chunks.get(Depth.chunkKey(wx >> 4, wz >> 4));
        if (c == null) return -1;
        return c.levelAt(wx & 15, wy, wz & 15);
    }

    private static int statusIndex(byte status) {
        return status >= 0 && status <= 4 ? status : 0;
    }

    /**
     * Histogram of what is currently stored for sections at or below ceilingY.
     * Indexes 0-4: sky status counts (absent, empty, zeroed, normal, uniform).
     * Indexes 5-9: the same for block light. Index 10: highest block light level seen.
     * Index 11: number of chunks inspected.
     */
    public int[] histogram(int ceilingY) {
        int[] h = new int[12];
        for (Chunk c : chunks.values()) {
            h[11]++;
            for (int i = 1; i <= c.sectionCount; i++) {
                int secY = Depth.sectionY(c.bottomSectionY, i);
                if (!Depth.sectionTouchesBelow(secY, ceilingY)) continue;
                h[statusIndex(c.sky[i])]++;
                h[5 + statusIndex(c.blockStatus[i])]++;
                byte[] a = c.block[i];
                if (a != null && a != LightCodec.ZERO) h[10] = Math.max(h[10], LightCodec.maxLevel(a));
            }
        }
        return h;
    }
}
