package dev.clicksclient.modules;

import dev.clicksclient.core.BlockSections;
import dev.clicksclient.core.ChunkQueue;
import dev.clicksclient.core.Depth;
import dev.clicksclient.core.LightEvent;
import dev.clicksclient.core.LightWorld;
import dev.clicksclient.mc.BlockScan;
import dev.clicksclient.mc.ClicksModule;
import dev.clicksclient.mc.Feature;
import dev.clicksclient.mc.LightService;
import dev.clicksclient.mc.MarkerRenderer;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Two ways around a server that hides what is below you.
 *
 * Hidden sections: below Y 0 natural terrain is almost never one repeated block, so a perfectly uniform
 * solid section is usually filler the server swapped in. The hiding is the tell.
 *
 * Reveal memory: when the server re-sends a chunk with the real blocks (it tends to when you get close),
 * spawners, storage and other watched blocks are remembered, and stay flagged after it hides them again.
 */
public class AntiEspBypass extends Feature {
    private final Setting<Boolean> detectHidden = bool("detect-hidden-sections",
        "Flag chunks where the server shows a perfectly uniform solid section below Y 0.", true);

    private final Setting<Integer> minHidden = num("min-hidden-sections",
        "How many uniform sections a chunk needs before it is flagged.", 1, 1, 16, 8);

    private final Setting<Boolean> confirmedOnly = bool("confirmed-only",
        "Only flag when the light data also shows something happened in the chunk, so both signals agree. Fewer false flags, but you will miss lone signals.", false);

    private final Setting<Boolean> sectionBoxes = bool("section-boxes",
        "Draw a box through the walls around each hidden section.", true);

    private final Setting<Integer> boxRange = num("section-box-range",
        "Only draw hidden-section boxes within this many chunks of you.", 12, 1, 128, 48, () -> sectionBoxes.get());

    private final Setting<Integer> maxSectionBoxes = num("max-section-boxes",
        "Most hidden-section boxes drawn at once.", 200, 10, 3000, 1000, () -> sectionBoxes.get());

    private final Setting<Boolean> remember = bool("remember-revealed",
        "Remember watched blocks the server shows you, and keep them flagged after it hides them again.", true);

    private final Setting<List<Block>> watch = blocks("watch-blocks",
        "Blocks to remember once the server reveals them.",
        new ArrayList<>(List.of(Blocks.SPAWNER, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL,
            Blocks.ENDER_CHEST, Blocks.SHULKER_BOX, Blocks.HOPPER, Blocks.BEACON)));

    private final Setting<Boolean> forgetMissing = bool("forget-missing",
        "Drop remembered blocks the server no longer shows. Leave off to keep them after it hides them again.", false);

    private final Setting<Boolean> memoryBoxes = bool("memory-boxes",
        "Box remembered blocks through the walls.", true);

    private final Setting<SettingColor> liveFill = color("live-fill",
        "Fill colour of remembered blocks the server is showing right now.", new SettingColor(255, 80, 80, 45), () -> memoryBoxes.get());

    private final Setting<SettingColor> liveLine = color("live-line",
        "Outline colour of remembered blocks the server is showing right now.", new SettingColor(255, 80, 80, 220), () -> memoryBoxes.get());

    private final Setting<SettingColor> ghostFill = color("hidden-again-fill",
        "Fill colour of remembered blocks the server has hidden again.", new SettingColor(140, 140, 255, 35), () -> memoryBoxes.get());

    private final Setting<SettingColor> ghostLine = color("hidden-again-line",
        "Outline colour of remembered blocks the server has hidden again.", new SettingColor(160, 160, 255, 220), () -> memoryBoxes.get());

    private final Setting<Integer> scansPerTick = num("chunks-per-tick",
        "How many chunks are scanned each tick.", 16, 1, 128, 64);

    private final Setting<Integer> pauseY = num("pause-below-y",
        "Ignore the light half of the confirmation while you are below this Y. -64 never pauses.",
        0, -64, 320, -64, 64, null);

    private static final class Revealed {
        Block block;
        final int x, y, z;
        boolean live;

        Revealed(Block block, int x, int y, int z, boolean live) {
            this.block = block;
            this.x = x;
            this.y = y;
            this.z = z;
            this.live = live;
        }
    }

    private final ChunkQueue queue = new ChunkQueue();
    private final Map<Long, byte[]> status = new HashMap<>();
    private final Map<Long, Integer> touched = new HashMap<>();
    private final Map<Long, Map<Long, Revealed>> memory = new HashMap<>();
    private final Color boxSide = new Color(0, 0, 0, 0);
    private final Color boxLine = new Color(0, 0, 0, 0);
    private int worldBottom = -4;
    private long scanned;
    private long resent;

    public AntiEspBypass(ClicksModule hub) {
        super(hub, "Anti-ESP Bypass", "anti-",
            "Finds what the server is hiding below Y 0: spots uniform filler sections, and remembers anything it reveals even after it hides it again.");
        addColors(
            new SettingColor(170, 80, 255, 40), new SettingColor(190, 110, 255, 200),
            new SettingColor(150, 120, 255, 255), new SettingColor(255, 60, 200, 255),
            new SettingColor(190, 110, 255, 200));
    }

    @Override
    protected void activate() {
        queueLoadedChunks(queue);
    }

    @Override
    protected void deactivate() {
        queue.clear();
    }

    @Override
    protected boolean usesLight() {
        return true;
    }

    @Override
    protected int lightPauseY() {
        return pauseY.get() <= -64 ? Integer.MIN_VALUE : pauseY.get();
    }

    private boolean paused() {
        return mc.player != null && pauseY.get() > -64 && mc.player.getY() < pauseY.get();
    }

    @Override
    protected void light(LightWorld.Chunk c, LightEvent e) {
        if (paused()) return;
        int t = c.touchedSections(ceiling(), false);
        Integer old = touched.put(c.key, t);
        if (old == null || old != t) evaluate(c.key);
    }

    @Override
    protected void unload(long key) {
        touched.remove(key);
    }

    @Override
    protected void reset() {
        touched.clear();
    }

    @Override
    protected void chunkData(ChunkDataEvent event) {
        WorldChunk c = event.chunk();
        if (c == null) return;
        long key = Depth.chunkKey(c.getPos().x, c.getPos().z);
        if (status.containsKey(key)) resent++; // the server sent a chunk we already had: it may now hold real data
        queue.add(key);
    }

    @Override
    protected void blockUpdate(BlockUpdateEvent event) {
        if (!remember.get()) return;
        BlockPos p = event.pos;
        if (p.getY() > ceiling()) return;
        List<Block> list = watch.get();
        Block now = event.newState.getBlock();
        if (list.contains(now)) {
            remember(p.getX(), p.getY(), p.getZ(), now, true);
            refreshMemoryFlag(Depth.chunkKey(p.getX() >> 4, p.getZ() >> 4));
        } else if (event.newState.isAir() && list.contains(event.oldState.getBlock())) {
            forget(p);
        }
    }

    @Override
    protected void tick() {
        if (!ready()) return;
        pauseHint(detectHidden.get() && confirmedOnly.get() && paused(), pauseY.get());
        if (status.size() > 60000) status.clear();
        int n = scansPerTick.get();
        while (n-- > 0 && !queue.isEmpty()) scan(queue.poll());
    }

    // ------------------------------------------------------------ scanning

    private void scan(long key) {
        ClientWorld w = mc.world;
        int cx = Depth.keyX(key), cz = Depth.keyZ(key);
        WorldChunk chunk = w.getChunkManager().getWorldChunk(cx, cz);
        if (chunk == null) return;
        worldBottom = w.getBottomSectionCoord();
        scanned++;
        if (detectHidden.get()) {
            status.put(key, BlockScan.classify(chunk, worldBottom, ceiling()));
            evaluate(key);
        }
        if (remember.get()) scanWatched(chunk, cx, cz, key);
    }

    private void evaluate(long key) {
        if (!detectHidden.get()) return;
        byte[] st = status.get(key);
        if (st == null) return;
        int ceiling = ceiling();
        int hidden = BlockSections.count(st, worldBottom, ceiling, BlockSections.HOMOGENEOUS_SOLID);
        if (hidden < minHidden.get()) return;
        int t = touched.getOrDefault(key, 0);
        boolean confirmed = t > 0;
        if (confirmedOnly.get() && !confirmed) return;

        int firstIdx = 0;
        for (int i = 0; i < st.length; i++) {
            if (st[i] == BlockSections.HOMOGENEOUS_SOLID && Depth.sectionTouchesBelow(worldBottom + i, ceiling)) {
                firstIdx = i;
                break;
            }
        }
        int cx = Depth.keyX(key), cz = Depth.keyZ(key);
        int ay = Math.min(((worldBottom + firstIdx) << 4) + 8, ceiling);
        float severity = Math.min(1f, 0.30f + hidden * 0.15f + (confirmed ? 0.35f : 0f));
        flag(cx, cz, (cx << 4) + 8, ay, (cz << 4) + 8, severity,
            (confirmed ? "CONFIRMED hidden base, " : "hidden section(s): ") + hidden);
    }

    private void scanWatched(WorldChunk chunk, int cx, int cz, long key) {
        List<Block> list = watch.get();
        if (list.isEmpty()) return;
        Predicate<BlockState> pred = st -> list.contains(st.getBlock());
        int ceiling = ceiling();
        Map<Long, Revealed> found = new HashMap<>();

        ChunkSection[] secs = chunk.getSectionArray();
        for (int i = 0; i < secs.length; i++) {
            int baseY = (worldBottom + i) << 4;
            if (baseY > ceiling) break;
            ChunkSection s = secs[i];
            if (s == null || s.isEmpty()) continue;
            if (!s.getBlockStateContainer().hasAny(pred)) continue;
            for (int y = 0; y < 16; y++) {
                int wy = baseY + y;
                if (wy > ceiling) break;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block b = s.getBlockState(x, y, z).getBlock();
                        if (!list.contains(b)) continue;
                        int wx = (cx << 4) + x, wz = (cz << 4) + z;
                        found.put(BlockPos.asLong(wx, wy, wz), new Revealed(b, wx, wy, wz, true));
                    }
                }
            }
        }
        // block entities are sent separately and can survive even when the block state is disguised
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockPos p = be.getPos();
            if (p.getY() > ceiling) continue;
            Block b = be.getCachedState().getBlock();
            if (list.contains(b)) found.putIfAbsent(p.asLong(), new Revealed(b, p.getX(), p.getY(), p.getZ(), true));
        }

        Map<Long, Revealed> mem = memory.computeIfAbsent(key, k -> new HashMap<>());
        for (Revealed r : mem.values()) r.live = false;
        for (Map.Entry<Long, Revealed> en : found.entrySet()) {
            Revealed old = mem.get(en.getKey());
            if (old == null) mem.put(en.getKey(), en.getValue());
            else {
                old.block = en.getValue().block;
                old.live = true;
            }
        }
        if (forgetMissing.get()) mem.values().removeIf(r -> !r.live);
        if (mem.isEmpty()) memory.remove(key);
        else refreshMemoryFlag(key);
    }

    private void remember(int x, int y, int z, Block b, boolean live) {
        long ck = Depth.chunkKey(x >> 4, z >> 4);
        Map<Long, Revealed> m = memory.computeIfAbsent(ck, k -> new HashMap<>());
        long pk = BlockPos.asLong(x, y, z);
        Revealed r = m.get(pk);
        if (r == null) m.put(pk, new Revealed(b, x, y, z, live));
        else {
            r.block = b;
            r.live = live;
        }
    }

    private void forget(BlockPos p) {
        long ck = Depth.chunkKey(p.getX() >> 4, p.getZ() >> 4);
        Map<Long, Revealed> m = memory.get(ck);
        if (m == null) return;
        m.remove(p.asLong());
        if (m.isEmpty()) memory.remove(ck);
    }

    private void refreshMemoryFlag(long key) {
        Map<Long, Revealed> m = memory.get(key);
        if (m == null || m.isEmpty()) return;
        Map<Block, Integer> counts = new HashMap<>();
        boolean spawner = false;
        Revealed first = null;
        int live = 0;
        for (Revealed r : m.values()) {
            counts.merge(r.block, 1, Integer::sum);
            if (r.block == Blocks.SPAWNER) spawner = true;
            if (first == null) first = r;
            if (r.live) live++;
        }
        List<Map.Entry<Block, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sorted.get(i).getValue()).append("x ").append(sorted.get(i).getKey().getName().getString());
        }
        float severity = spawner ? 0.85f : Math.min(0.7f, 0.25f + m.size() * 0.05f);
        flag(Depth.keyX(key), Depth.keyZ(key), first.x, first.y, first.z, severity,
            "remembered " + sb + (live == 0 ? " (hidden again)" : ""));
    }

    // ------------------------------------------------------------ rendering

    @Override
    protected void render(Render3DEvent event) {
        renderFlags(event);
        if (mc.player == null) return;
        double px = mc.player.getX(), pz = mc.player.getZ();
        int pcx = ((int) Math.floor(px)) >> 4;
        int pcz = ((int) Math.floor(pz)) >> 4;

        if (detectHidden.get() && sectionBoxes.get() && !status.isEmpty()) {
            int range = boxRange.get();
            int budget = maxSectionBoxes.get();
            int ceiling = ceiling();
            MarkerRenderer.colours(hub.marker, colors, 1f, boxSide, boxLine);
            for (Map.Entry<Long, byte[]> en : status.entrySet()) {
                int cx = Depth.keyX(en.getKey()), cz = Depth.keyZ(en.getKey());
                if (Math.abs(cx - pcx) > range || Math.abs(cz - pcz) > range) continue;
                byte[] st = en.getValue();
                for (int i = 0; i < st.length && budget > 0; i++) {
                    if (st[i] != BlockSections.HOMOGENEOUS_SOLID) continue;
                    int y = (worldBottom + i) << 4;
                    if (y > ceiling) break;
                    double top = Math.min(y + 16, ceiling + 1);
                    event.renderer.box(cx << 4, y, cz << 4, (cx << 4) + 16, top, (cz << 4) + 16, boxSide, boxLine, hub.marker.shapeMode.get(), 0);
                    budget--;
                }
                if (budget <= 0) break;
            }
        }

        if (remember.get() && memoryBoxes.get() && !memory.isEmpty()) {
            int range = hub.marker.renderDistance.get();
            int budget = 400;
            for (Map.Entry<Long, Map<Long, Revealed>> en : memory.entrySet()) {
                if (Math.abs(Depth.keyX(en.getKey()) - pcx) > range || Math.abs(Depth.keyZ(en.getKey()) - pcz) > range) continue;
                for (Revealed r : en.getValue().values()) {
                    if (budget-- <= 0) return;
                    SettingColor fill = r.live ? liveFill.get() : ghostFill.get();
                    SettingColor line = r.live ? liveLine.get() : ghostLine.get();
                    event.renderer.box(r.x, r.y, r.z, r.x + 1, r.y + 1, r.z + 1, fill, line, hub.marker.shapeMode.get(), 0);
                }
            }
        }
    }

    @Override
    protected void cleared() {
        memory.clear();
        status.clear();
        touched.clear();
        queue.clear();
        queueLoadedChunks(queue);
    }

    @Override
    protected String diag() {
        int hiddenChunks = 0;
        int ceiling = ceiling();
        for (byte[] st : status.values()) {
            if (BlockSections.count(st, worldBottom, ceiling, BlockSections.HOMOGENEOUS_SOLID) >= minHidden.get()) hiddenChunks++;
        }
        int remembered = 0;
        for (Map<Long, Revealed> m : memory.values()) remembered += m.size();
        return "scanned chunks=" + scanned + " re-sent chunks seen=" + resent + " chunks with hidden sections=" + hiddenChunks
            + " remembered blocks=" + remembered + " in " + memory.size() + " chunks"
            + (paused() ? " [light paused]" : "")
            + " | " + LightService.INSTANCE.diag();
    }
}
