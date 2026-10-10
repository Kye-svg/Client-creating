package dev.clicksclient.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds light emitters (torches, lanterns, lamps, lava...) from raw block-light arrays.
 * Light falls off by one per block, so an emitter shows up as a local maximum. Equal-level
 * neighbours (a lava lake, a block of lamps) are merged into one source.
 */
public final class LightSourceFinder {
    private LightSourceFinder() {}

    public static final class Source {
        public final int minX, minY, minZ, maxX, maxY, maxZ; // inclusive world block coordinates
        public final int level, cells;
        public double scratch; // renderer scratch space (distance sorting)

        Source(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int level, int cells) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.level = level;
            this.cells = cells;
        }

        public double centerX() { return (minX + maxX + 1) / 2.0; }
        public double centerY() { return (minY + maxY + 1) / 2.0; }
        public double centerZ() { return (minZ + maxZ + 1) / 2.0; }
    }

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    /**
     * @param ceilingY highest block Y reported (always -1 or lower in this addon)
     * @param minLevel ignore anything dimmer than this
     * @param maxCells ignore merged plateaus bigger than this (natural lava lakes)
     * @param cap      most sources returned for one chunk
     */
    public static List<Source> find(LightWorld world, LightWorld.Chunk chunk, int ceilingY, int minLevel, int maxCells, int cap) {
        List<Source> out = new ArrayList<>();
        for (int idx = 1; idx <= chunk.sectionCount; idx++) {
            int secY = Depth.sectionY(chunk.bottomSectionY, idx);
            if (!Depth.sectionTouchesBelow(secY, ceilingY)) continue;
            byte[] arr = chunk.block[idx];
            if (arr == null || arr == LightCodec.ZERO) continue;
            if (LightCodec.maxLevel(arr) < minLevel) continue;
            scanSection(world, chunk, secY, arr, ceilingY, minLevel, maxCells, out);
        }
        out.sort(Comparator.comparingInt((Source s) -> -s.level).thenComparingInt(s -> s.cells));
        if (out.size() > cap) return new ArrayList<>(out.subList(0, cap));
        return out;
    }

    private static void scanSection(LightWorld world, LightWorld.Chunk chunk, int secY, byte[] arr,
                                    int ceilingY, int minLevel, int maxCells, List<Source> out) {
        final int baseX = chunk.x << 4, baseY = secY << 4, baseZ = chunk.z << 4;
        int[] lv = new int[4096];
        for (int i = 0; i < 4096; i++) lv[i] = LightCodec.nibbleAt(arr, i);

        boolean[] cand = new boolean[4096];
        for (int y = 0; y < 16; y++) {
            if (baseY + y > ceilingY) break;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int i = y << 8 | z << 4 | x;
                    int level = lv[i];
                    if (level < minLevel) continue;
                    cand[i] = isPeak(world, lv, x, y, z, baseX, baseY, baseZ, level);
                }
            }
        }

        boolean[] seen = new boolean[4096];
        int[] stack = new int[4096];
        for (int start = 0; start < 4096; start++) {
            if (!cand[start] || seen[start]) continue;
            int level = lv[start];
            int sp = 0;
            stack[sp++] = start;
            seen[start] = true;
            int minX = 99, minY = 99, minZ = 99, maxX = -1, maxY = -1, maxZ = -1, cells = 0;
            while (sp > 0) {
                int c = stack[--sp];
                cells++;
                int cx = c & 15, cz = (c >> 4) & 15, cy = c >> 8;
                if (cx < minX) minX = cx;
                if (cx > maxX) maxX = cx;
                if (cy < minY) minY = cy;
                if (cy > maxY) maxY = cy;
                if (cz < minZ) minZ = cz;
                if (cz > maxZ) maxZ = cz;
                for (int d = 0; d < 6; d++) {
                    int nx = cx + DX[d], ny = cy + DY[d], nz = cz + DZ[d];
                    if (nx < 0 || nx > 15 || ny < 0 || ny > 15 || nz < 0 || nz > 15) continue;
                    int ni = ny << 8 | nz << 4 | nx;
                    if (cand[ni] && !seen[ni] && lv[ni] == level) {
                        seen[ni] = true;
                        stack[sp++] = ni;
                    }
                }
            }
            if (cells <= maxCells) {
                out.add(new Source(baseX + minX, baseY + minY, baseZ + minZ, baseX + maxX, baseY + maxY, baseZ + maxZ, level, cells));
            }
        }
    }

    private static boolean isPeak(LightWorld world, int[] lv, int x, int y, int z, int baseX, int baseY, int baseZ, int level) {
        boolean unknown = false;
        for (int d = 0; d < 6; d++) {
            int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
            int v;
            if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16) {
                v = lv[ny << 8 | nz << 4 | nx];
            } else {
                v = world.levelAt(baseX + nx, baseY + ny, baseZ + nz);
            }
            if (v < 0) unknown = true;
            else if (v > level) return false;
        }
        // A cell on an unloaded border can only be trusted if it is already at the maximum possible level.
        return !(unknown && level < 15);
    }
}
