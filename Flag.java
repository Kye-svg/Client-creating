package dev.clicksclient.core;

/** One flagged chunk. */
public final class Flag {
    public final long key;
    public final int chunkX, chunkZ;
    public int ax, ay, az;                      // anchor: the exact detection point
    public float severity;                      // 0..1, drives the colour gradient
    public String label;
    public long firstSeen, lastSeen;
    public int hits;
    public int surfaceY = Integer.MIN_VALUE;    // ground height above the chunk, when known
    public boolean test;                        // created by the test command
    public double scratch;                      // renderer scratch space (distance sorting)

    public Flag(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.key = Depth.chunkKey(chunkX, chunkZ);
    }
}
