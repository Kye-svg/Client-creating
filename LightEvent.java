package dev.clicksclient.core;

import java.util.BitSet;
import java.util.List;

/**
 * One light payload (from a chunk packet or a light-update packet), reduced to something small.
 * Built on the network thread; consumed on the main thread.
 */
public final class LightEvent {
    /** Only the lowest section indexes are kept; everything of interest is near the bottom of the world. */
    public static final int KEEP_INDEXES = 12;

    public final int chunkX, chunkZ;
    public final boolean full;
    public final long nowMs;
    public final byte[] sky;          // status per section index
    public final byte[] blockStatus;  // status per section index
    public final byte[][] block;      // null = not in packet, LightCodec.ZERO = empty mask, else the array

    private LightEvent(int cx, int cz, boolean full, long nowMs, byte[] sky, byte[] blockStatus, byte[][] block) {
        this.chunkX = cx;
        this.chunkZ = cz;
        this.full = full;
        this.nowMs = nowMs;
        this.sky = sky;
        this.blockStatus = blockStatus;
        this.block = block;
    }

    public static LightEvent build(int cx, int cz, boolean full,
                                   BitSet initedSky, BitSet uninitedSky, List<byte[]> skyNibbles,
                                   BitSet initedBlock, BitSet uninitedBlock, List<byte[]> blockNibbles,
                                   long nowMs) {
        int len = Math.max(Math.max(initedSky.length(), uninitedSky.length()),
            Math.max(initedBlock.length(), uninitedBlock.length()));
        int n = Math.min(KEEP_INDEXES, len);

        byte[] sky = new byte[n];
        byte[] blockStatus = new byte[n];
        byte[][] block = new byte[n][];

        int k = 0;
        for (int i = initedSky.nextSetBit(0); i >= 0; i = initedSky.nextSetBit(i + 1)) {
            byte[] arr = k < skyNibbles.size() ? skyNibbles.get(k) : null;
            k++; // the list is ordered by set bit, so always advance
            if (i < n) sky[i] = LightCodec.classify(arr);
        }
        for (int i = uninitedSky.nextSetBit(0); i >= 0 && i < n; i = uninitedSky.nextSetBit(i + 1)) {
            sky[i] = LightCodec.EMPTY;
        }

        k = 0;
        for (int i = initedBlock.nextSetBit(0); i >= 0; i = initedBlock.nextSetBit(i + 1)) {
            byte[] arr = k < blockNibbles.size() ? blockNibbles.get(k) : null;
            k++;
            if (i < n) {
                byte st = LightCodec.classify(arr);
                blockStatus[i] = st == LightCodec.ABSENT ? LightCodec.ZEROED : st;
                block[i] = st == LightCodec.ABSENT ? LightCodec.ZERO : arr;
            }
        }
        for (int i = uninitedBlock.nextSetBit(0); i >= 0 && i < n; i = uninitedBlock.nextSetBit(i + 1)) {
            blockStatus[i] = LightCodec.EMPTY;
            block[i] = LightCodec.ZERO;
        }
        return new LightEvent(cx, cz, full, nowMs, sky, blockStatus, block);
    }
}
