package dev.clicksclient.core;

/** Decoding helpers for the nibble arrays the server sends in light packets. */
public final class LightCodec {
    private LightCodec() {}

    /** Section status codes. */
    public static final byte ABSENT = 0;  // not described by the packet
    public static final byte EMPTY = 1;   // listed in the "empty" mask (no array sent)
    public static final byte ZEROED = 2;  // array sent but every nibble is 0
    public static final byte NORMAL = 3;  // array with mixed values
    public static final byte UNIFORM = 4; // array where every nibble is 15

    public static final int SIZE = 2048;

    /** Shared read-only array meaning "known to be all zero". Never mutate. */
    public static final byte[] ZERO = new byte[SIZE];

    public static byte classify(byte[] a) {
        if (a == null || a.length != SIZE) return ABSENT;
        boolean allZero = true, allMax = true;
        for (byte b : a) {
            if (b != 0) allZero = false;
            if (b != -1) allMax = false;
            if (!allZero && !allMax) return NORMAL;
        }
        return allZero ? ZEROED : UNIFORM;
    }

    /** Light level at section-local x,y,z (0-15 each). Matches vanilla's ChunkNibbleArray layout. */
    public static int nibble(byte[] a, int x, int y, int z) {
        return nibbleAt(a, (y & 15) << 8 | (z & 15) << 4 | (x & 15));
    }

    public static int nibbleAt(byte[] a, int index) {
        if (a == null) return 0;
        int b = a[index >> 1];
        return (index & 1) == 0 ? (b & 15) : ((b >> 4) & 15);
    }

    public static int maxLevel(byte[] a) {
        if (a == null) return 0;
        int max = 0;
        for (byte b : a) {
            int lo = b & 15, hi = (b >> 4) & 15;
            if (lo > max) max = lo;
            if (hi > max) max = hi;
            if (max == 15) return 15;
        }
        return max;
    }
}
