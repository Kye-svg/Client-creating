package dev.clicksclient.core;

/**
 * Depth rules shared by every module. "Below Y 0" means a block Y of -1 or lower.
 * Light packets address a column as section indexes: index 0 is the padding section under the
 * world, index 1 is the bottom real section, and so on.
 */
public final class Depth {
    private Depth() {}

    /** Highest block Y that still counts as being below Y 0. */
    public static final int CEILING = -1;

    /** Same packing as ChunkPos.toLong. */
    public static long chunkKey(int x, int z) {
        return (x & 0xFFFFFFFFL) | ((z & 0xFFFFFFFFL) << 32);
    }

    public static int keyX(long key) {
        return (int) key;
    }

    public static int keyZ(long key) {
        return (int) (key >> 32);
    }

    /** Light-packet section index to section Y. */
    public static int sectionY(int bottomSectionY, int index) {
        return bottomSectionY + index - 1;
    }

    /** True when the section holds at least one block at or below ceilingY. */
    public static boolean sectionTouchesBelow(int sectionY, int ceilingY) {
        return (sectionY << 4) <= ceilingY;
    }

    /** Sections kept in memory: everything below Y 0 plus the one section above it (needed for neighbour lookups). */
    public static boolean retainSection(int sectionY) {
        return sectionY <= 0;
    }
}
