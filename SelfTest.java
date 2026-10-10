import dev.clicksclient.core.ClusterEngine;
import dev.clicksclient.core.Depth;
import dev.clicksclient.core.Flag;
import dev.clicksclient.core.FlagStore;
import dev.clicksclient.core.FlickerEngine;
import dev.clicksclient.core.LightCodec;
import dev.clicksclient.core.LightEvent;
import dev.clicksclient.core.LightSourceFinder;
import dev.clicksclient.core.LightWorld;
import dev.clicksclient.core.WindowCounter;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/** Run with: java -cp out SelfTest. Exits non-zero if anything fails. */
public class SelfTest {
    static int pass = 0, fail = 0;

    static void check(boolean cond, String name) {
        if (cond) pass++;
        else {
            fail++;
            System.out.println("FAIL: " + name);
        }
    }

    static void eq(Object got, Object want, String name) {
        check(Objects.equals(got, want), name + " (want " + want + ", got " + got + ")");
    }

    // ---------------------------------------------------------------- helpers

    static byte[] zeros() {
        return new byte[LightCodec.SIZE];
    }

    static void setCell(byte[] a, int x, int y, int z, int level) {
        int idx = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        int b = a[idx >> 1];
        if ((idx & 1) == 0) b = (b & 0xF0) | level;
        else b = (b & 0x0F) | (level << 4);
        a[idx >> 1] = (byte) b;
    }

    /** Open-air light field: level = max over sources of (source level - manhattan distance). */
    static class Field {
        final List<int[]> src = new ArrayList<>();

        Field add(int x, int y, int z, int level) {
            src.add(new int[]{x, y, z, level});
            return this;
        }

        int level(int x, int y, int z) {
            int best = 0;
            for (int[] s : src) {
                int d = Math.abs(x - s[0]) + Math.abs(y - s[1]) + Math.abs(z - s[2]);
                best = Math.max(best, s[3] - d);
            }
            return best;
        }

        byte[] section(int cx, int secY, int cz) {
            byte[] a = zeros();
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int l = level(cx * 16 + x, secY * 16 + y, cz * 16 + z);
                        if (l > 0) setCell(a, x, y, z, l);
                    }
            return a;
        }
    }

    static final int BOTTOM = -4, SECTIONS = 24;

    /** What the server sends when a chunk loads: arrays for lit sections, empty mask for dark ones. */
    static LightEvent fullEvent(Field f, int cx, int cz, long now) {
        BitSet iSky = new BitSet(), uSky = new BitSet(), iBlk = new BitSet(), uBlk = new BitSet();
        List<byte[]> skyL = new ArrayList<>(), blkL = new ArrayList<>();
        for (int i = 1; i <= 5; i++) { // sections -4..0
            int secY = BOTTOM + i - 1;
            uSky.set(i);
            byte[] a = f.section(cx, secY, cz);
            if (LightCodec.maxLevel(a) > 0) {
                iBlk.set(i);
                blkL.add(a);
            } else uBlk.set(i);
        }
        return LightEvent.build(cx, cz, true, iSky, uSky, skyL, iBlk, uBlk, blkL, now);
    }

    /** A light-update packet carrying only one block-light section. */
    static LightEvent blockUpdate(int cx, int cz, int index, byte[] arr, long now) {
        BitSet none = new BitSet(), iBlk = new BitSet();
        iBlk.set(index);
        List<byte[]> blk = new ArrayList<>();
        blk.add(arr);
        return LightEvent.build(cx, cz, false, none, new BitSet(), new ArrayList<>(), iBlk, new BitSet(), blk, now);
    }

    static LightWorld worldWith(Field f, int radius, long now) {
        LightWorld w = new LightWorld();
        for (int cx = -radius; cx <= radius; cx++)
            for (int cz = -radius; cz <= radius; cz++)
                w.apply(fullEvent(f, cx, cz, now), BOTTOM, SECTIONS);
        return w;
    }

    static List<LightSourceFinder.Source> find(LightWorld w, int cx, int cz, int minLevel, int maxCells) {
        return LightSourceFinder.find(w, w.get(cx, cz), -1, minLevel, maxCells, 64);
    }

    // ------------------------------------------------------------------ tests

    static void testDepth() {
        for (int[] p : new int[][]{{0, 0}, {-1, -1}, {5, -9}, {-1875000, 1875000}, {123456, -654321}}) {
            long k = Depth.chunkKey(p[0], p[1]);
            eq(Depth.keyX(k), p[0], "keyX " + p[0]);
            eq(Depth.keyZ(k), p[1], "keyZ " + p[1]);
        }
        check(Depth.sectionTouchesBelow(-1, -1), "section -1 is below Y 0");
        check(!Depth.sectionTouchesBelow(0, -1), "section 0 is not below Y 0");
        check(Depth.sectionTouchesBelow(-4, -1), "section -4 is below Y 0");
        eq(Depth.sectionY(-4, 1), -4, "index 1 is the bottom section");
        eq(Depth.sectionY(-4, 5), 0, "index 5 is section 0");
        check(Depth.retainSection(0) && !Depth.retainSection(1), "retain keeps sections <= 0");
    }

    static void testWindowCounter() {
        WindowCounter c = new WindowCounter();
        for (int i = 0; i < 20; i++) c.add(1000);
        eq(c.total(1000, 10), 20, "burst total");
        eq(c.activeSeconds(1000, 10), 1, "burst is one active second");

        WindowCounter clock = new WindowCounter();
        for (int s = 1000; s < 1008; s++) clock.add(s);
        eq(clock.total(1007, 10), 8, "1 Hz clock total");
        eq(clock.activeSeconds(1007, 10), 8, "1 Hz clock active seconds");
        eq(clock.total(1020, 10), 0, "old events fall out of the window");
        check(clock.isStale(1020, 10), "stale after the window passes");

        WindowCounter fast = new WindowCounter();
        for (int s = 2000; s < 2004; s++) for (int i = 0; i < 20; i++) fast.add(s);
        eq(fast.total(2003, 10), 80, "20 Hz clock total");
        eq(fast.activeSeconds(2003, 10), 4, "20 Hz clock active seconds");
    }

    static void testCodec() {
        eq(LightCodec.classify(null), LightCodec.ABSENT, "null array");
        eq(LightCodec.classify(new byte[5]), LightCodec.ABSENT, "wrong size");
        eq(LightCodec.classify(zeros()), LightCodec.ZEROED, "all zero");
        byte[] full = zeros();
        java.util.Arrays.fill(full, (byte) -1);
        eq(LightCodec.classify(full), LightCodec.UNIFORM, "all fifteen");
        byte[] mixed = zeros();
        setCell(mixed, 3, 5, 7, 9);
        eq(LightCodec.classify(mixed), LightCodec.NORMAL, "mixed");

        byte[] a = zeros();
        setCell(a, 3, 5, 7, 13);  // idx = 5<<8|7<<4|3 = 1395 (odd -> high nibble)
        setCell(a, 4, 5, 7, 6);   // even -> low nibble
        eq(LightCodec.nibble(a, 3, 5, 7), 13, "odd index decode");
        eq(LightCodec.nibble(a, 4, 5, 7), 6, "even index decode");
        eq(LightCodec.nibble(a, 0, 0, 0), 0, "untouched cell");
        eq(LightCodec.maxLevel(a), 13, "max level");
        eq(LightCodec.nibble(null, 1, 1, 1), 0, "null array reads as dark");
    }

    static void testEventOrdering() {
        BitSet iSky = new BitSet(), uSky = new BitSet(), none = new BitSet();
        iSky.set(1);
        iSky.set(3);
        iSky.set(5);
        uSky.set(2);
        byte[] full = zeros();
        java.util.Arrays.fill(full, (byte) -1);
        byte[] mixed = zeros();
        setCell(mixed, 1, 1, 1, 4);
        List<byte[]> sky = new ArrayList<>();
        sky.add(zeros());
        sky.add(full);
        sky.add(mixed);
        LightEvent e = LightEvent.build(1, 2, true, iSky, uSky, sky, none, none, new ArrayList<>(), 0);
        eq(e.sky[1], LightCodec.ZEROED, "sky[1]");
        eq(e.sky[2], LightCodec.EMPTY, "sky[2]");
        eq(e.sky[3], LightCodec.UNIFORM, "sky[3]");
        eq(e.sky[4], LightCodec.ABSENT, "sky[4]");
        eq(e.sky[5], LightCodec.NORMAL, "sky[5]");

        // bits beyond the kept range must still consume their array so later ones stay aligned
        BitSet far = new BitSet();
        far.set(1);
        far.set(14);
        far.set(15);
        List<byte[]> three = new ArrayList<>();
        three.add(mixed);
        three.add(zeros());
        three.add(zeros());
        LightEvent f = LightEvent.build(0, 0, true, far, new BitSet(), three, none, none, new ArrayList<>(), 0);
        eq(f.sky[1], LightCodec.NORMAL, "far bits keep alignment");
        eq(f.sky.length, LightEvent.KEEP_INDEXES, "event length is capped");
    }

    static void testWorldMerge() {
        Field f = new Field().add(5, -40, 9, 14);
        LightWorld w = new LightWorld();
        w.apply(fullEvent(f, 0, 0, 1000), BOTTOM, SECTIONS);
        LightWorld.Chunk c = w.get(0, 0);
        check(c != null, "chunk stored");
        eq(w.levelAt(5, -40, 9), 14, "torch level readable");
        eq(w.levelAt(6, -40, 9), 13, "falloff readable");
        eq(w.levelAt(300, -40, 9), -1, "unknown chunk is -1");
        eq(w.levelAt(5, -200, 9), 0, "below the world is dark");
        eq(w.levelAt(5, 400, 9), 0, "above retained range is dark");
        eq(c.loadedAtMs, 1000L, "load time recorded");

        // a partial update only replaces the section it lists
        int idx = (-40 >> 4) - BOTTOM + 1;
        w.apply(blockUpdate(0, 0, idx, zeros(), 2000), BOTTOM, SECTIONS);
        eq(w.levelAt(5, -40, 9), 0, "torch removed by partial update");
        check(w.get(0, 0).block[idx + 1] != null, "other sections untouched");
        eq(w.get(0, 0).loadedAtMs, 1000L, "partial update keeps load time");

        // a full packet resets the load time (this is what a server re-send looks like)
        w.apply(fullEvent(f, 0, 0, 3000), BOTTOM, SECTIONS);
        eq(w.levelAt(5, -40, 9), 14, "full packet restores state");
        eq(w.get(0, 0).loadedAtMs, 3000L, "re-send refreshes load time");

        final boolean[] unloaded = {false};
        w.addListener(new LightWorld.Listener() {
            @Override
            public void onUnload(long key) {
                unloaded[0] = true;
            }
        });
        w.unload(Depth.chunkKey(0, 0));
        check(unloaded[0] && w.get(0, 0) == null, "unload removes chunk and notifies");
    }

    static void testSourceFinder() {
        // single torch
        Field f = new Field().add(5, -40, 9, 14);
        LightWorld w = worldWith(f, 1, 0);
        List<LightSourceFinder.Source> s = find(w, 0, 0, 8, 48);
        eq(s.size(), 1, "one torch -> one source");
        LightSourceFinder.Source t = s.get(0);
        check(t.minX == 5 && t.minY == -40 && t.minZ == 9 && t.maxX == 5 && t.maxY == -40 && t.maxZ == 9, "torch exact position");
        eq(t.level, 14, "torch level");
        eq(t.cells, 1, "torch is one cell");
        eq(find(w, 1, 0, 8, 48).size() + find(w, -1, 0, 8, 48).size() + find(w, 0, 1, 8, 48).size(), 0, "neighbour chunks report nothing");

        // below the minimum level
        eq(find(w, 0, 0, 15, 48).size(), 0, "min level filters dim sources");
        Field dim = new Field().add(5, -40, 9, 7);
        eq(find(worldWith(dim, 1, 0), 0, 0, 8, 48).size(), 0, "level 7 below min level 8");

        // lava lake plateau
        Field lava = new Field();
        for (int x = 4; x < 10; x++) for (int z = 4; z < 10; z++) lava.add(x, -54, z, 15);
        LightWorld lw = worldWith(lava, 1, 0);
        List<LightSourceFinder.Source> lakes = find(lw, 0, 0, 8, 48);
        eq(lakes.size(), 1, "plateau merges into one source");
        eq(lakes.get(0).cells, 36, "plateau cell count");
        eq(find(lw, 0, 0, 8, 20).size(), 0, "big plateau filtered by max cells");

        // chunk border: torch on the edge with its glow spilling into the next chunk
        Field edge = new Field().add(15, -40, 9, 14);
        LightWorld both = worldWith(edge, 1, 0);
        eq(find(both, 0, 0, 8, 48).size(), 1, "edge torch found in its own chunk");
        eq(both.levelAt(16, -40, 9), 13, "glow reaches the next chunk");
        eq(find(both, 1, 0, 8, 48).size(), 0, "spill-over is not a source");

        // neighbour not loaded yet: the dim border cell must be deferred, not reported
        LightWorld onlyB = new LightWorld();
        onlyB.apply(fullEvent(edge, 1, 0, 0), BOTTOM, SECTIONS);
        eq(find(onlyB, 1, 0, 8, 48).size(), 0, "border cell with unknown neighbour is deferred");
        onlyB.apply(fullEvent(edge, 0, 0, 0), BOTTOM, SECTIONS);
        eq(find(onlyB, 0, 0, 8, 48).size(), 1, "found once the neighbour arrives");
        eq(find(onlyB, 1, 0, 8, 48).size(), 0, "still no false source next door");

        // a level-15 cell is trusted even with unknown neighbours (nothing can be brighter)
        Field bright = new Field().add(15, -40, 9, 15);
        LightWorld lone = new LightWorld();
        lone.apply(fullEvent(bright, 0, 0, 0), BOTTOM, SECTIONS);
        eq(find(lone, 0, 0, 8, 48).size(), 1, "level 15 trusted on unloaded border");

        // vertical section boundary
        Field vert = new Field().add(8, -16, 8, 14);
        eq(find(worldWith(vert, 1, 0), 0, 0, 8, 48).size(), 1, "torch on a section boundary");

        // top layer of the below-zero range, section above known
        Field top = new Field().add(8, -1, 8, 14);
        List<LightSourceFinder.Source> topS = find(worldWith(top, 1, 0), 0, 0, 8, 48);
        eq(topS.size(), 1, "torch at y -1 is reported");
        eq(topS.get(0).minY, -1, "torch at y -1 position");

        // torch at y 0 is above the line: never reported, and its glow at y -1 is not mistaken for a source
        Field zero = new Field().add(8, 0, 8, 14);
        eq(find(worldWith(zero, 1, 0), 0, 0, 8, 48).size(), 0, "torch at y 0 is ignored");

        // two torches in one chunk, strongest first
        Field two = new Field().add(3, -30, 3, 12).add(12, -30, 12, 14);
        List<LightSourceFinder.Source> both2 = find(worldWith(two, 1, 0), 0, 0, 8, 48);
        eq(both2.size(), 2, "two torches found");
        eq(both2.get(0).level, 14, "brightest first");
    }

    static void testSourceFinderRandom() {
        Random r = new Random(12345);
        int bad = 0;
        for (int it = 0; it < 150; it++) {
            int x = r.nextInt(16), z = r.nextInt(16), y = -64 + r.nextInt(64);
            int level = 9 + r.nextInt(7);
            Field f = new Field().add(x, y, z, level);
            LightWorld w = worldWith(f, 1, 0);
            List<LightSourceFinder.Source> got = find(w, 0, 0, 8, 48);
            boolean ok = got.size() == 1 && got.get(0).minX == x && got.get(0).minY == y && got.get(0).minZ == z && got.get(0).level == level;
            for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}})
                if (!find(w, n[0], n[1], 8, 48).isEmpty()) ok = false;
            if (!ok) {
                bad++;
                if (bad < 4) System.out.println("  random miss at " + x + "," + y + "," + z + " L" + level + " got=" + got.size());
            }
        }
        eq(bad, 0, "random single sources: exact position, no strays (150 cases)");
    }

    static void testFlicker() {
        FlickerEngine.Params p = new FlickerEngine.Params(); // window 10, toggles 6, active 3, light 8, light active 4
        FlickerEngine e = new FlickerEngine();

        FlickerEngine.Hit h = null;
        int hitSecond = -1;
        for (int s = 0; s < 10 && h == null; s++) {
            h = e.onFlip(77L, 5L, 5000 + s, p);
            if (h != null) hitSecond = s;
        }
        check(h != null && hitSecond == 5, "1 Hz clock flags on the 6th toggle (got second " + hitSecond + ")");

        FlickerEngine burst = new FlickerEngine();
        FlickerEngine.Hit bh = null;
        for (int i = 0; i < 40; i++) bh = burst.onFlip(1L, 1L, 9000, p);
        check(bh == null, "a single-second burst (someone mashing a lever) is rejected");

        FlickerEngine fast = new FlickerEngine();
        FlickerEngine.Hit fh = null;
        for (int s = 0; s < 4; s++) for (int i = 0; i < 20; i++) { FlickerEngine.Hit x = fast.onFlip(2L, 2L, 7000 + s, p); if (x != null) fh = x; }
        check(fh != null, "a 20 Hz clock is caught");

        FlickerEngine slow = new FlickerEngine();
        FlickerEngine.Hit sh = null;
        for (int s = 0; s < 60; s += 5) { FlickerEngine.Hit x = slow.onFlip(3L, 3L, 100 + s, p); if (x != null) sh = x; }
        check(sh == null, "a toggle every 5 s is not a flicker");

        FlickerEngine light = new FlickerEngine();
        FlickerEngine.Hit lh = null;
        int lightSecond = -1;
        for (int s = 0; s < 12 && lh == null; s++) {
            lh = light.onLight(42L, 3, 26, 8000 + s, p);
            if (lh != null) lightSecond = s;
        }
        check(lh != null && lh.fromLight && lh.index == 3 && lightSecond == 7, "light updates once a second flag on the 8th (got " + lightSecond + ")");

        FlickerEngine lburst = new FlickerEngine();
        FlickerEngine.Hit lb = null;
        for (int i = 0; i < 50; i++) lb = lburst.onLight(42L, 3, 26, 8000, p);
        check(lb == null, "a one-second light burst (placing a few torches) is rejected");

        e.sweep(99999, 10);
        eq(e.trackedBlocks(), 0, "sweep drops stale block counters");
        light.sweep(99999, 10);
        eq(light.trackedChunks(), 0, "sweep drops stale light counters");
        light.onLight(1L, 1, 26, 5, p);
        light.dropChunk(1L);
        eq(light.trackedChunks(), 0, "dropChunk removes a chunk");
    }

    static void testClusters() {
        ClusterEngine ce = new ClusterEngine();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) ce.set(Depth.chunkKey(x, z), true);
        ce.set(Depth.chunkKey(10, 10), true);
        List<List<Long>> cl = ce.clusters(4, true);
        eq(cl.size(), 1, "3x3 block is one cluster, the lone chunk is dropped");
        eq(cl.get(0).size(), 9, "cluster size");

        ClusterEngine d = new ClusterEngine();
        for (int i = 0; i < 6; i++) d.set(Depth.chunkKey(i, i), true); // pure diagonal
        eq(d.clusters(5, true).size(), 1, "diagonal chain joins when diagonals are on");
        eq(d.clusters(2, false).size(), 0, "diagonal chain does not join when diagonals are off");

        ClusterEngine two = new ClusterEngine();
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) { two.set(Depth.chunkKey(x, z), true); two.set(Depth.chunkKey(x + 50, z), true); }
        eq(two.clusters(5, true).size(), 0, "two small separate groups do not add up");
        eq(two.clusters(4, true).size(), 2, "each group qualifies on its own at min 4");

        check(ce.set(Depth.chunkKey(0, 0), false), "set(false) reports a change");
        check(!ce.set(Depth.chunkKey(0, 0), false), "set(false) twice reports no change");
    }

    static void testFlagStore() {
        FlagStore fs = new FlagStore();
        fs.selectWorld("minecraft:overworld");
        check(fs.flag(3, 4, 10, -30, 20, 0.4f, "first", 1000), "new flag reported as new");
        check(!fs.flag(3, 4, 11, -31, 21, 0.2f, "weaker", 2000), "refresh is not new");
        eq(fs.get(Depth.chunkKey(3, 4)).label, "first", "weaker refresh keeps the stronger label");
        eq(fs.get(Depth.chunkKey(3, 4)).lastSeen, 2000L, "refresh updates last seen");
        fs.flag(3, 4, 12, -32, 22, 0.9f, "stronger", 3000);
        eq(fs.get(Depth.chunkKey(3, 4)).label, "stronger", "stronger refresh replaces the label");
        eq(fs.get(Depth.chunkKey(3, 4)).ay, -32, "stronger refresh moves the anchor");

        fs.selectWorld("minecraft:the_nether");
        eq(fs.size(), 0, "other dimension starts empty");
        fs.flag(1, 1, 0, -5, 0, 0.1f, "nether", 5);
        fs.selectWorld("minecraft:overworld");
        eq(fs.size(), 1, "overworld flags survive the dimension hop");

        for (int i = 0; i < 20; i++) fs.flag(100 + i, 0, 0, -5, 0, 0.1f, "x", 10_000 + i);
        fs.prune(20_000, 0, 5);
        eq(fs.size(), 5, "prune trims to the cap");
        fs.prune(1_000_000, 500, 100);
        eq(fs.size(), 0, "prune expires old flags");

        Flag t = new Flag(0, 0);
        fs.flag(0, 0, 0, -1, 0, 1f, "t", 1);
        fs.get(Depth.chunkKey(0, 0)).test = true;
        fs.flag(9, 9, 0, -1, 0, 1f, "real", 1);
        fs.clearTests();
        eq(fs.size(), 1, "clearTests removes only test flags");
        check(t.key == Depth.chunkKey(0, 0), "flag key matches chunk key");
    }

    /** Whole pipeline as the modules run it: packets in, flags out. */
    static void testPipelines() {
        // --- Light ESP: a base with torches shows up as a flagged chunk with exact coordinates
        // two torches in chunk (2,4) and one lantern in chunk (3,4) -> exactly two flagged chunks
        Field base = new Field().add(40, -30, 71, 14).add(44, -30, 71, 14).add(60, -33, 75, 15);
        LightWorld w = new LightWorld();
        for (int cx = 1; cx <= 4; cx++) for (int cz = 3; cz <= 6; cz++) w.apply(fullEvent(base, cx, cz, 0), BOTTOM, SECTIONS);
        FlagStore flags = new FlagStore();
        flags.selectWorld("overworld");
        for (LightWorld.Chunk c : w.all()) {
            List<LightSourceFinder.Source> src = LightSourceFinder.find(w, c, -1, 8, 48, 64);
            if (!src.isEmpty()) flags.flag(c.x, c.z, (int) src.get(0).centerX(), (int) src.get(0).centerY(), (int) src.get(0).centerZ(), 0.5f, src.size() + " light sources", 1);
        }
        eq(flags.size(), 2, "light ESP flags the two chunks holding the base");
        check(flags.get(Depth.chunkKey(2, 4)) != null && flags.get(Depth.chunkKey(2, 4)).label.startsWith("2 "), "chunk (2,4) holds two torches");
        check(flags.get(Depth.chunkKey(2, 4)).ay < 0, "anchor is below Y 0");

        // --- Redstone: light updates once a second in one section flag that chunk
        LightWorld rw = new LightWorld();
        rw.apply(fullEvent(new Field(), 7, 7, 0), BOTTOM, SECTIONS);
        FlickerEngine eng = new FlickerEngine();
        FlickerEngine.Params p = new FlickerEngine.Params();
        FlagStore rf = new FlagStore();
        rf.selectWorld("overworld");
        int idx = (-20 >> 4) - BOTTOM + 1;
        for (int s = 0; s < 12; s++) {
            long now = 50_000L + s * 1000L;
            LightEvent ev = blockUpdate(7, 7, idx, zeros(), now);
            rw.apply(ev, BOTTOM, SECTIONS);
            LightWorld.Chunk c = rw.get(7, 7);
            for (int i : c.changedIndexes(ev, -1, false, true)) {
                FlickerEngine.Hit h = eng.onLight(c.key, i, c.sectionCount + 2, (int) (now / 1000), p);
                if (h != null) rf.flag(c.x, c.z, 7 * 16 + 8, -20, 7 * 16 + 8, 0.5f, "flicker " + h.count, now);
            }
        }
        eq(rf.size(), 1, "redstone inspector flags the flickering chunk");

        // update packets for sections above Y 0 never count
        LightWorld.Chunk rc = rw.get(7, 7);
        int hi = 10; // a section well above the world's below-zero range
        LightEvent above = blockUpdate(7, 7, hi, zeros(), 99_000L);
        eq(rc.changedIndexes(above, -1, false, true).length, 0, "updates above Y 0 are ignored");

        // --- Huge area: a 3x3 patch of chunks whose deep sections are all 'touched' becomes one cluster
        LightWorld hw = new LightWorld();
        ClusterEngine cluster = new ClusterEngine();
        for (int cx = 20; cx < 23; cx++)
            for (int cz = 20; cz < 23; cz++) {
                BitSet iSky = new BitSet(), uSky = new BitSet(), none = new BitSet();
                List<byte[]> skyL = new ArrayList<>();
                iSky.set(2);
                skyL.add(zeros());   // sent but all zero -> touched
                iSky.set(3);
                skyL.add(zeros());
                uSky.set(1);
                uSky.set(4);
                LightEvent ev = LightEvent.build(cx, cz, true, iSky, uSky, skyL, none, none, new ArrayList<>(), 0);
                hw.apply(ev, BOTTOM, SECTIONS);
                LightWorld.Chunk c = hw.get(cx, cz);
                eq(c.touchedSections(-1, false), 2, "touched count for " + cx + "," + cz);
                cluster.set(c.key, c.touchedSections(-1, false) >= 2);
            }
        // a pristine neighbour: everything via the empty mask
        BitSet none = new BitSet(), uAll = new BitSet();
        uAll.set(1, 5);
        hw.apply(LightEvent.build(23, 20, true, none, uAll, new ArrayList<>(), none, none, new ArrayList<>(), 0), BOTTOM, SECTIONS);
        cluster.set(hw.get(23, 20).key, hw.get(23, 20).touchedSections(-1, false) >= 2);
        List<List<Long>> found = cluster.clusters(4, true);
        eq(found.size(), 1, "huge area: one cluster");
        eq(found.get(0).size(), 9, "huge area: pristine neighbour is not part of it");

        // block-light arrays only count when asked for
        BitSet iBlk = new BitSet();
        iBlk.set(2);
        List<byte[]> bl = new ArrayList<>();
        bl.add(zeros());
        hw.apply(LightEvent.build(30, 30, true, none, uAll, new ArrayList<>(), iBlk, new BitSet(), bl, 0), BOTTOM, SECTIONS);
        eq(hw.get(30, 30).touchedSections(-1, false), 0, "block arrays ignored by default");
        eq(hw.get(30, 30).touchedSections(-1, true), 1, "block arrays counted when enabled");

        // --- Motion: three light updates inside a second flag a chunk (low thresholds, one active second is enough)
        FlickerEngine motion = new FlickerEngine();
        FlickerEngine.Params mp = new FlickerEngine.Params();
        mp.windowSec = 6;
        mp.minLight = 3;
        mp.minLightActive = 1;
        FlickerEngine.Hit mh = null;
        for (int i = 0; i < 3; i++) mh = motion.onLight(9L, 2, 26, 1234, mp);
        check(mh != null, "motion scanner threshold reached by a short burst");
        FlickerEngine quiet = new FlickerEngine();
        FlickerEngine.Hit qh = null;
        for (int i = 0; i < 2; i++) qh = quiet.onLight(9L, 2, 26, 1234, mp);
        check(qh == null, "two updates are not motion");

        // --- Light ESP stays live: when the torch is removed the source disappears
        Field one = new Field().add(5, -40, 9, 14);
        LightWorld lw = worldWith(one, 1, 0);
        eq(find(lw, 0, 0, 8, 48).size(), 1, "source present");
        int tIdx = (-40 >> 4) - BOTTOM + 1;
        lw.apply(blockUpdate(0, 0, tIdx, zeros(), 10), BOTTOM, SECTIONS);
        eq(find(lw, 0, 0, 8, 48).size(), 0, "source gone after the light update");
    }


    static final class Fake implements dev.clicksclient.core.BlockSections.Section {
        final String[] cells = new String[4096];
        boolean empty;

        Fake fill(String v) {
            java.util.Arrays.fill(cells, v);
            return this;
        }

        @Override public boolean isEmpty() { return empty; }
        @Override public Object stateAt(int x, int y, int z) { return cells[y << 8 | z << 4 | x]; }
        @Override public boolean isSolidFiller(Object state) { return !"air".equals(state) && !"water".equals(state); }
        @Override public boolean isAir(Object state) { return "air".equals(state); }
    }

    static void testBlockSections() {
        eq(dev.clicksclient.core.BlockSections.classify(new Fake().fill("deepslate")), dev.clicksclient.core.BlockSections.HOMOGENEOUS_SOLID, "uniform deepslate is hidden filler");
        Fake mixed = new Fake().fill("deepslate");
        mixed.cells[4095] = "iron_ore"; // difference only in the very last cell
        eq(dev.clicksclient.core.BlockSections.classify(mixed), dev.clicksclient.core.BlockSections.MIXED, "one different block makes it natural");
        Fake first = new Fake().fill("stone");
        first.cells[1] = "tuff";
        eq(dev.clicksclient.core.BlockSections.classify(first), dev.clicksclient.core.BlockSections.MIXED, "early difference is caught");
        Fake empty = new Fake();
        empty.empty = true;
        eq(dev.clicksclient.core.BlockSections.classify(empty), dev.clicksclient.core.BlockSections.HOMOGENEOUS_NONSOLID, "all air is not filler");
        eq(dev.clicksclient.core.BlockSections.classify(new Fake().fill("water")), dev.clicksclient.core.BlockSections.HOMOGENEOUS_NONSOLID, "uniform water is not filler");
        eq(dev.clicksclient.core.BlockSections.classify(new Fake().fill("air")), dev.clicksclient.core.BlockSections.HOMOGENEOUS_NONSOLID, "uniform air is not filler");

        byte[] st = {dev.clicksclient.core.BlockSections.MIXED, dev.clicksclient.core.BlockSections.HOMOGENEOUS_SOLID, dev.clicksclient.core.BlockSections.HOMOGENEOUS_SOLID, dev.clicksclient.core.BlockSections.MIXED,
            dev.clicksclient.core.BlockSections.HOMOGENEOUS_SOLID, dev.clicksclient.core.BlockSections.HOMOGENEOUS_SOLID};
        // bottom section -4 -> indexes 0..5 are sections -4..1; only -4..-1 are below Y 0
        eq(dev.clicksclient.core.BlockSections.count(st, -4, -1, dev.clicksclient.core.BlockSections.HOMOGENEOUS_SOLID), 2, "hidden count only looks below Y 0");
        eq(dev.clicksclient.core.BlockSections.count(st, -4, 100, dev.clicksclient.core.BlockSections.HOMOGENEOUS_SOLID), 4, "wider ceiling counts more");

        // cleared layers: need 217 of 256 blocks to be air (85%)
        Fake carved = new Fake().fill("stone");
        for (int y = 3; y < 8; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) carved.cells[y << 8 | z << 4 | x] = "air"; // layers 3..7 fully open
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) carved.cells[10 << 8 | z << 4 | x] = (z < 14 ? "air" : "stone"); // layer 10: 224 air, still counts
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) carved.cells[12 << 8 | z << 4 | x] = (z < 13 ? "air" : "stone"); // layer 12: 208 air, does not
        eq(dev.clicksclient.core.BlockSections.clearedLayers(carved, 16, 217), 6, "cleared layers counted (5 full + 1 at 87%)");
        eq(dev.clicksclient.core.BlockSections.clearedLayers(carved, 5, 217), 2, "only the lowest requested layers are looked at");
        eq(dev.clicksclient.core.BlockSections.clearedLayers(new Fake().fill("stone"), 16, 217), 0, "solid terrain has no cleared layers");

        // dual signal: a chunk whose sky data is touched AND which has hidden sections is the confirmed case
        LightWorld w = new LightWorld();
        BitSet iSky = new BitSet(), uSky = new BitSet(), none = new BitSet();
        iSky.set(2);
        uSky.set(1);
        List<byte[]> skyL = new ArrayList<>();
        skyL.add(zeros());
        w.apply(LightEvent.build(5, 5, true, iSky, uSky, skyL, none, none, new ArrayList<>(), 0), BOTTOM, SECTIONS);
        boolean lightTouched = w.get(5, 5).touchedSections(-1, false) >= 1;
        boolean hidden = dev.clicksclient.core.BlockSections.count(st, -4, -1, dev.clicksclient.core.BlockSections.HOMOGENEOUS_SOLID) >= 1;
        check(lightTouched && hidden, "dual-signal (confirmed) condition holds");
    }

    public static void main(String[] args) {
        testDepth();
        testWindowCounter();
        testCodec();
        testEventOrdering();
        testWorldMerge();
        testSourceFinder();
        testSourceFinderRandom();
        testFlicker();
        testClusters();
        testFlagStore();
        testPipelines();
        testBlockSections();
        System.out.println("passed " + pass + ", failed " + fail);
        if (fail > 0) System.exit(1);
    }
}
