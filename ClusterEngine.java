package dev.clicksclient.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Groups neighbouring "open" chunks so a huge area shows up as one cluster rather than scattered chunks. */
public final class ClusterEngine {
    private final Set<Long> open = new HashSet<>();

    /** Returns true if the state actually changed. */
    public boolean set(long key, boolean isOpen) {
        return isOpen ? open.add(key) : open.remove(key);
    }

    public void remove(long key) {
        open.remove(key);
    }

    public void clear() {
        open.clear();
    }

    public int openCount() {
        return open.size();
    }

    public int size() {
        return open.size();
    }

    public List<List<Long>> clusters(int minChunks, boolean diagonals) {
        List<List<Long>> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long start : open) {
            if (seen.contains(start)) continue;
            List<Long> group = new ArrayList<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            seen.add(start);
            while (!queue.isEmpty()) {
                long k = queue.poll();
                group.add(k);
                int x = Depth.keyX(k), z = Depth.keyZ(k);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        if (!diagonals && dx != 0 && dz != 0) continue;
                        long nk = Depth.chunkKey(x + dx, z + dz);
                        if (open.contains(nk) && seen.add(nk)) queue.add(nk);
                    }
                }
            }
            if (group.size() >= minChunks) result.add(group);
        }
        return result;
    }
}
