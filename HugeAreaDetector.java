package dev.clicksclient.modules;

import dev.clicksclient.core.BlockSections;
import dev.clicksclient.core.ChunkQueue;
import dev.clicksclient.core.ClusterEngine;
import dev.clicksclient.core.Depth;
import dev.clicksclient.core.LightEvent;
import dev.clicksclient.core.LightWorld;
import dev.clicksclient.mc.BlockScan;
import dev.clicksclient.mc.ClicksModule;
import dev.clicksclient.mc.Feature;
import dev.clicksclient.mc.LightService;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds big excavated or built-over areas below Y 0. A chunk counts as "open" when any enabled signal
 * says so, and neighbouring open chunks are grouped: a lone open chunk is noise, a cluster is an area.
 *
 * Signals: light sections that were touched, whole layers that are cleared, and filler sections the
 * server substituted. Light works at any distance; the block signals only work where the server shows
 * real blocks (usually near you).
 */
public class HugeAreaDetector extends Feature {
    private final Setting<Boolean> useLight = bool("use-light",
        "Count sections whose light data shows something happened there. Works at any distance.", true);

    private final Setting<Integer> minTouched = num("min-touched-sections",
        "Touched light sections a chunk needs to count as open.", 2, 1, 16, 8, () -> useLight.get());

    private final Setting<Boolean> useBlocks = bool("use-blocks",
        "Count chunks with fully cleared layers. Needs the server to be showing real blocks, so mostly near you.", true);

    private final Setting<Integer> layerPct = num("layer-open-percent",
        "How much of a 16x16 layer must be air for the layer to count as cleared.", 85, 50, 100, 100, () -> useBlocks.get());

    private final Setting<Integer> minCleared = num("min-cleared-layers",
        "Cleared layers a chunk needs to count as open.", 6, 1, 64, 64, () -> useBlocks.get());

    private final Setting<Boolean> countEmpty = bool("count-empty-sections",
        "Treat a section that is entirely air as 16 cleared layers.", true, () -> useBlocks.get());

    private final Setting<Boolean> useHidden = bool("use-hidden-sections",
        "Count chunks where the server shows uniform filler sections instead of real terrain.", true);

    private final Setting<Integer> minHidden = num("min-hidden-sections",
        "Hidden sections a chunk needs to count as open.", 1, 1, 16, 8, () -> useHidden.get());

    private final Setting<Integer> minCluster = num("min-cluster-chunks",
        "Neighbouring open chunks needed before it counts as a huge area. 4 is roughly a 32x32 block area.", 4, 1, 64, 32);

    private final Setting<Boolean> diagonals = bool("join-diagonals",
        "Chunks touching only at a corner still count as neighbours.", true);

    private final Setting<Integer> scansPerTick = num("chunks-per-tick",
        "How many chunks the block signals scan each tick.", 6, 1, 64, 32);

    private final Setting<Integer> pauseY = num("pause-below-y",
        "Ignore light data while you are below this Y, because your own digging would count. -64 never pauses.",
        0, -64, 320, -64, 64, null);

    private final ClusterEngine cluster = new ClusterEngine();
    private final Map<Long, int[]> info = new HashMap<>(); // per chunk: touched, cleared layers, hidden sections
    private final ChunkQueue queue = new ChunkQueue();
    private final Set<Long> alerted = new HashSet<>();
    private boolean dirty;
    private int clusterTicks;
    private int reevalTicks;
    private long scanned;
    private int lastClusters;

    public HugeAreaDetector(ClicksModule hub) {
        super(hub, "Huge Area Detector", "huge-",
            "Flags big excavated or built-over areas below Y 0 by grouping neighbouring chunks that show signs of it.");
        addColors(
            new SettingColor(0, 170, 255, 40), new SettingColor(40, 190, 255, 200),
            new SettingColor(0, 220, 200, 255), new SettingColor(60, 90, 255, 255),
            new SettingColor(40, 190, 255, 200));
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
        return useLight.get();
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
        int[] a = info.computeIfAbsent(c.key, k -> new int[3]);
        if (a[0] != t) {
            a[0] = t;
            updateOpen(c.key);
        }
    }

    @Override
    protected void reset() {
        info.clear();
        cluster.clear();
        alerted.clear();
    }

    @Override
    protected void chunkData(ChunkDataEvent event) {
        WorldChunk c = event.chunk();
        if (c == null) return;
        queue.add(Depth.chunkKey(c.getPos().x, c.getPos().z));
    }

    @Override
    protected void tick() {
        if (!ready()) return;
        pauseHint(useLight.get() && paused(), pauseY.get());
        if (info.size() > 60000) {
            info.clear();
            cluster.clear();
        }

        int n = scansPerTick.get();
        while (n-- > 0 && !queue.isEmpty()) scan(queue.poll());

        if (++reevalTicks >= 100) {
            reevalTicks = 0;
            for (Long key : new HashSet<>(info.keySet())) updateOpen(key); // picks up threshold changes
        }
        if (++clusterTicks >= 20) {
            clusterTicks = 0;
            if (dirty) {
                dirty = false;
                evaluateClusters();
            }
        }
    }

    private void scan(long key) {
        ClientWorld w = mc.world;
        WorldChunk chunk = w.getChunkManager().getWorldChunk(Depth.keyX(key), Depth.keyZ(key));
        if (chunk == null) return;
        int bottom = w.getBottomSectionCoord();
        int ceiling = ceiling();
        int[] a = info.computeIfAbsent(key, k -> new int[3]);
        if (useBlocks.get()) {
            int need = (int) Math.ceil(layerPct.get() / 100.0 * 256.0);
            a[1] = BlockScan.clearedLayers(chunk, bottom, ceiling, need, countEmpty.get());
        }
        if (useHidden.get()) {
            a[2] = BlockSections.count(BlockScan.classify(chunk, bottom, ceiling), bottom, ceiling, BlockSections.HOMOGENEOUS_SOLID);
        }
        scanned++;
        updateOpen(key);
    }

    private void updateOpen(long key) {
        int[] a = info.get(key);
        if (a == null) return;
        boolean open = (useLight.get() && a[0] >= minTouched.get())
            || (useBlocks.get() && a[1] >= minCleared.get())
            || (useHidden.get() && a[2] >= minHidden.get());
        if (cluster.set(key, open)) dirty = true;
    }

    private void evaluateClusters() {
        List<List<Long>> found = cluster.clusters(minCluster.get(), diagonals.get());
        lastClusters = found.size();
        int ay = Math.min(-16, ceiling());
        for (List<Long> group : found) {
            boolean fresh = true;
            long sx = 0, sz = 0;
            for (long k : group) {
                if (alerted.contains(k)) fresh = false;
                sx += Depth.keyX(k);
                sz += Depth.keyZ(k);
            }
            float severity = Math.min(1f, group.size() / (float) Math.max(1, minCluster.get() * 4));
            String label = "huge area, " + group.size() + " chunks";
            for (long k : group) {
                int cx = Depth.keyX(k), cz = Depth.keyZ(k);
                flagQuiet(cx, cz, (cx << 4) + 8, ay, (cz << 4) + 8, severity, label);
            }
            if (fresh) {
                alert(label, (int) ((sx / group.size()) << 4) + 8, ay, (int) ((sz / group.size()) << 4) + 8);
                alerted.addAll(group);
            }
        }
    }

    @Override
    protected void render(Render3DEvent event) {
        renderFlags(event);
    }

    @Override
    protected void cleared() {
        info.clear();
        cluster.clear();
        alerted.clear();
        queue.clear();
        queueLoadedChunks(queue);
    }

    @Override
    protected String diag() {
        return "open chunks=" + cluster.openCount() + " clusters=" + lastClusters + " block-scanned chunks=" + scanned
            + " tracked chunks=" + info.size() + (useLight.get() && paused() ? " [light paused]" : "")
            + " | " + LightService.INSTANCE.diag();
    }
}
