package dev.clicksclient.mc;

import dev.clicksclient.core.BlockSections;
import dev.clicksclient.core.Depth;
import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/** Reads block data out of loaded chunks. Main thread only. */
public final class BlockScan {
    private BlockScan() {}

    private static final class Adapter implements BlockSections.Section {
        ChunkSection section;

        @Override
        public boolean isEmpty() {
            return section.isEmpty();
        }

        @Override
        public Object stateAt(int x, int y, int z) {
            return section.getBlockState(x, y, z);
        }

        @Override
        public boolean isSolidFiller(Object state) {
            BlockState s = (BlockState) state;
            return !s.isAir() && s.getFluidState().isEmpty();
        }

        @Override
        public boolean isAir(Object state) {
            return ((BlockState) state).isAir();
        }
    }

    private static final Adapter ADAPTER = new Adapter();

    /** Status of every section (same indexing as WorldChunk.getSectionArray) at or below ceilingY; the rest stay UNKNOWN. */
    public static byte[] classify(WorldChunk chunk, int bottomSectionY, int ceilingY) {
        ChunkSection[] secs = chunk.getSectionArray();
        byte[] out = new byte[secs.length];
        for (int i = 0; i < secs.length; i++) {
            if (!Depth.sectionTouchesBelow(bottomSectionY + i, ceilingY)) break;
            ChunkSection s = secs[i];
            if (s == null) continue;
            ADAPTER.section = s;
            out[i] = BlockSections.classify(ADAPTER);
        }
        ADAPTER.section = null;
        return out;
    }

    /**
     * Counts horizontal 16x16 layers at or below ceilingY where at least 'need' of the 256 blocks are air.
     * A fully empty section counts as 16 layers when countEmpty is set.
     */
    public static int clearedLayers(WorldChunk chunk, int bottomSectionY, int ceilingY, int need, boolean countEmpty) {
        ChunkSection[] secs = chunk.getSectionArray();
        int cleared = 0;
        for (int i = 0; i < secs.length; i++) {
            int baseY = (bottomSectionY + i) << 4;
            if (baseY > ceilingY) break;
            ChunkSection s = secs[i];
            if (s == null) continue;
            int layers = Math.min(16, ceilingY - baseY + 1);
            if (s.isEmpty()) {
                if (countEmpty) cleared += layers;
                continue;
            }
            ADAPTER.section = s;
            cleared += BlockSections.clearedLayers(ADAPTER, layers, need);
        }
        ADAPTER.section = null;
        return cleared;
    }
}
