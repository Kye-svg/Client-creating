package dev.clicksclient.core;

/**
 * Classifies a 16x16x16 block section. Natural terrain below Y 0 is almost never a single repeated
 * block, so a perfectly uniform solid section is usually filler the server substituted to hide what is
 * really there. That makes the hiding itself a tell.
 */
public final class BlockSections {
    private BlockSections() {}

    public static final byte UNKNOWN = 0;
    public static final byte MIXED = 1;
    public static final byte HOMOGENEOUS_SOLID = 2;
    public static final byte HOMOGENEOUS_NONSOLID = 3;

    /** Read-only view of one section; the game glue adapts a ChunkSection to this. */
    public interface Section {
        /** Palette-level shortcut: the section holds nothing but air. */
        boolean isEmpty();

        Object stateAt(int x, int y, int z);

        /** True for a non-air, non-fluid state (the kind of block used as filler). */
        boolean isSolidFiller(Object state);

        boolean isAir(Object state);
    }

    public static byte classify(Section s) {
        if (s.isEmpty()) return HOMOGENEOUS_NONSOLID;
        Object first = s.stateAt(0, 0, 0);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!first.equals(s.stateAt(x, y, z))) return MIXED; // exits on the first difference
                }
            }
        }
        return s.isSolidFiller(first) ? HOMOGENEOUS_SOLID : HOMOGENEOUS_NONSOLID;
    }

    /**
     * Counts horizontal 16x16 layers (the lowest maxLayers of the section) where at least 'need' of the 256
     * blocks are air. Gives up on a layer as soon as it has too many solid blocks, so natural terrain, which
     * fails almost immediately, is cheap to scan.
     */
    public static int clearedLayers(Section s, int maxLayers, int need) {
        int maxSolid = 256 - need;
        int cleared = 0;
        for (int y = 0; y < maxLayers; y++) {
            int solid = 0;
            boolean ok = true;
            scan:
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!s.isAir(s.stateAt(x, y, z)) && ++solid > maxSolid) {
                        ok = false;
                        break scan;
                    }
                }
            }
            if (ok) cleared++;
        }
        return cleared;
    }

    /** Counts sections with the wanted status among those at or below ceilingY. */
    public static int count(byte[] status, int bottomSectionY, int ceilingY, byte wanted) {
        int n = 0;
        for (int i = 0; i < status.length; i++) {
            if (!Depth.sectionTouchesBelow(bottomSectionY + i, ceilingY)) continue;
            if (status[i] == wanted) n++;
        }
        return n;
    }
}
