package dev.clicksclient.mc;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.Heightmap;

/** Ground height lookups so markers can sit above the surface. */
public final class Surface {
    private Surface() {}

    /** Top solid/liquid block Y above the middle of the chunk, or Integer.MIN_VALUE when the chunk is not loaded. */
    public static int topY(ClientWorld world, int chunkX, int chunkZ) {
        if (world == null) return Integer.MIN_VALUE;
        if (world.getChunkManager().getWorldChunk(chunkX, chunkZ) == null) return Integer.MIN_VALUE;
        return world.getTopY(Heightmap.Type.WORLD_SURFACE, (chunkX << 4) + 8, (chunkZ << 4) + 8);
    }
}
