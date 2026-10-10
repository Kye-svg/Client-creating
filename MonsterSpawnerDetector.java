package dev.clicksclient.modules;

import dev.clicksclient.core.ChunkQueue;
import dev.clicksclient.core.Depth;
import dev.clicksclient.mc.ClicksModule;
import dev.clicksclient.mc.Feature;
import dev.clicksclient.mc.MarkerRenderer;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds monster spawners below Y 0. Spawners are block entities, which servers usually still send even
 * when the block state around them is disguised, so both are checked. The server re-sends chunks as you
 * approach, so every chunk packet triggers a fresh scan, and spawners are remembered after they vanish
 * from view.
 */
public class MonsterSpawnerDetector extends Feature {
    private final Setting<Boolean> scanEntities = bool("scan-block-entities",
        "Look for spawner block entities. Best at seeing through disguised blocks.", true);

    private final Setting<Boolean> scanBlocks = bool("scan-block-states",
        "Also look for spawner blocks in the chunk data.", true);

    private final Setting<Boolean> identify = bool("identify-mob",
        "Work out which mob each spawner spawns.", true);

    private final Setting<String> mobFilter = text("mob-filter",
        "Only show spawners whose mob matches one of these words, comma separated, e.g. zombie, skeleton, blaze. Empty shows all.", "");

    private final Setting<Integer> minSpawners = num("min-spawners",
        "Flag a chunk once it holds at least this many spawners.", 1, 1, 64, 16);

    private final Setting<Integer> highCount = num("high-count",
        "Spawners in one chunk at which the marker reaches its high colour.", 4, 2, 64, 16);

    private final Setting<Boolean> forgetMissing = bool("forget-missing",
        "Drop spawners a later scan no longer sees. Leave off to remember them after the server hides them again.", false);

    private final Setting<Boolean> spawnerBoxes = bool("spawner-boxes",
        "Box every spawner through the walls.", true);

    private final Setting<Integer> scansPerTick = num("chunks-per-tick",
        "How many chunks are scanned each tick.", 6, 1, 64, 32);

    private static final class Spawner {
        final int x, y, z;
        String mob;

        Spawner(int x, int y, int z, String mob) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.mob = mob;
        }
    }

    private static final String UNKNOWN = "Spawner";

    private final Map<Long, Map<Long, Spawner>> byChunk = new HashMap<>();
    private final Map<Long, Integer> retries = new HashMap<>();
    private final ChunkQueue queue = new ChunkQueue();
    private final Color boxSide = new Color(0, 0, 0, 0);
    private final Color boxLine = new Color(0, 0, 0, 0);
    private int retryTicks;
    private long scanned;

    public MonsterSpawnerDetector(ClicksModule hub) {
        super(hub, "Monster Spawner Detector", "spawner-",
            "Finds monster spawners below Y 0, tells you which mob they spawn, and remembers them.");
        addColors(
            new SettingColor(60, 255, 90, 40), new SettingColor(80, 255, 110, 200),
            new SettingColor(140, 255, 80, 255), new SettingColor(255, 230, 0, 255),
            new SettingColor(80, 255, 110, 200));
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
    protected void chunkData(ChunkDataEvent event) {
        WorldChunk c = event.chunk();
        if (c == null) return;
        queue.add(Depth.chunkKey(c.getPos().x, c.getPos().z)); // every packet, including re-sends
    }

    @Override
    protected void blockUpdate(BlockUpdateEvent event) {
        BlockPos p = event.pos;
        if (p.getY() > ceiling()) return;
        long ck = Depth.chunkKey(p.getX() >> 4, p.getZ() >> 4);
        if (event.newState.isOf(Blocks.SPAWNER)) {
            Map<Long, Spawner> m = byChunk.computeIfAbsent(ck, k -> new HashMap<>());
            if (!m.containsKey(p.asLong())) m.put(p.asLong(), new Spawner(p.getX(), p.getY(), p.getZ(), UNKNOWN));
            retries.put(ck, 5);
            queue.add(ck); // the block entity usually arrives just after the block
            refresh(ck);
        } else if (event.oldState.isOf(Blocks.SPAWNER)) {
            Map<Long, Spawner> m = byChunk.get(ck);
            if (m != null) {
                m.remove(p.asLong());
                if (m.isEmpty()) byChunk.remove(ck);
            }
        }
    }

    @Override
    protected void tick() {
        if (!ready()) return;
        int n = scansPerTick.get();
        while (n-- > 0 && !queue.isEmpty()) scan(queue.poll());

        if (++retryTicks >= 40 && !retries.isEmpty()) {
            retryTicks = 0;
            for (Map.Entry<Long, Integer> en : new ArrayList<>(retries.entrySet())) {
                if (en.getValue() <= 1) retries.remove(en.getKey());
                else retries.put(en.getKey(), en.getValue() - 1);
                queue.add(en.getKey());
            }
        }
    }

    private void scan(long key) {
        ClientWorld w = mc.world;
        int cx = Depth.keyX(key), cz = Depth.keyZ(key);
        WorldChunk chunk = w.getChunkManager().getWorldChunk(cx, cz);
        if (chunk == null) return;
        scanned++;
        int ceiling = ceiling();
        int bottom = w.getBottomSectionCoord();
        Map<Long, Spawner> found = new HashMap<>();

        if (scanEntities.get()) {
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (!(be instanceof MobSpawnerBlockEntity sp)) continue;
                BlockPos p = be.getPos();
                if (p.getY() > ceiling) continue;
                found.put(p.asLong(), new Spawner(p.getX(), p.getY(), p.getZ(), identify.get() ? mobOf(w, p, sp) : UNKNOWN));
            }
        }

        if (scanBlocks.get()) {
            ChunkSection[] secs = chunk.getSectionArray();
            for (int i = 0; i < secs.length; i++) {
                int baseY = (bottom + i) << 4;
                if (baseY > ceiling) break;
                ChunkSection s = secs[i];
                if (s == null || s.isEmpty()) continue;
                if (!s.getBlockStateContainer().hasAny(st -> st.isOf(Blocks.SPAWNER))) continue;
                for (int y = 0; y < 16; y++) {
                    int wy = baseY + y;
                    if (wy > ceiling) break;
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!s.getBlockState(x, y, z).isOf(Blocks.SPAWNER)) continue;
                            int wx = (cx << 4) + x, wz = (cz << 4) + z;
                            long pk = BlockPos.asLong(wx, wy, wz);
                            if (found.containsKey(pk)) continue;
                            String mob = UNKNOWN;
                            if (identify.get()) {
                                BlockPos pos = new BlockPos(wx, wy, wz);
                                BlockEntity be = w.getBlockEntity(pos);
                                if (be instanceof MobSpawnerBlockEntity sp) mob = mobOf(w, pos, sp);
                            }
                            found.put(pk, new Spawner(wx, wy, wz, mob));
                        }
                    }
                }
            }
        }

        Map<Long, Spawner> mem = byChunk.get(key);
        if (mem == null || forgetMissing.get()) {
            if (found.isEmpty()) byChunk.remove(key);
            else byChunk.put(key, found);
        } else {
            for (Map.Entry<Long, Spawner> en : found.entrySet()) {
                Spawner old = mem.get(en.getKey());
                if (old == null) mem.put(en.getKey(), en.getValue());
                else if (!UNKNOWN.equals(en.getValue().mob)) old.mob = en.getValue().mob;
            }
        }

        Map<Long, Spawner> now = byChunk.get(key);
        if (now != null) {
            for (Spawner s : now.values()) {
                if (UNKNOWN.equals(s.mob) && identify.get()) {
                    retries.merge(key, 3, Math::max);
                    break;
                }
            }
        }
        refresh(key);
    }

    private String mobOf(ClientWorld w, BlockPos p, MobSpawnerBlockEntity be) {
        try {
            Entity e = be.getLogic().getRenderedEntity(w, p);
            if (e != null) return e.getType().getName().getString();
        } catch (Throwable ignored) {
            // not synced yet; the retry will try again
        }
        return UNKNOWN;
    }

    private boolean passes(String mob) {
        String f = mobFilter.get();
        if (f == null || f.isBlank()) return true;
        String m = mob.toLowerCase();
        for (String token : f.toLowerCase().split(",")) {
            String t = token.trim();
            if (!t.isEmpty() && m.contains(t)) return true;
        }
        return UNKNOWN.equals(mob); // unresolved spawners are kept until they can be identified
    }

    private void refresh(long key) {
        Map<Long, Spawner> m = byChunk.get(key);
        if (m == null) return;
        Map<String, Integer> counts = new HashMap<>();
        Spawner first = null;
        int total = 0;
        for (Spawner s : m.values()) {
            if (!passes(s.mob)) continue;
            counts.merge(s.mob, 1, Integer::sum);
            if (first == null) first = s;
            total++;
        }
        if (first == null || total < minSpawners.get()) return;

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(sorted.get(i).getValue()).append("x ").append(sorted.get(i).getKey());
        }
        float severity = Math.min(1f, total / (float) Math.max(2, highCount.get()));
        flag(Depth.keyX(key), Depth.keyZ(key), first.x, first.y, first.z, severity,
            sb + (sorted.size() == 1 && UNKNOWN.equals(sorted.get(0).getKey()) ? "" : " spawner(s)"));
    }

    @Override
    protected void render(Render3DEvent event) {
        renderFlags(event);
        if (!spawnerBoxes.get() || byChunk.isEmpty() || mc.player == null) return;
        int pcx = ((int) Math.floor(mc.player.getX())) >> 4;
        int pcz = ((int) Math.floor(mc.player.getZ())) >> 4;
        int range = hub.marker.renderDistance.get();
        int budget = 400;
        for (Map.Entry<Long, Map<Long, Spawner>> en : byChunk.entrySet()) {
            if (Math.abs(Depth.keyX(en.getKey()) - pcx) > range || Math.abs(Depth.keyZ(en.getKey()) - pcz) > range) continue;
            float sev = Math.min(1f, en.getValue().size() / (float) Math.max(2, highCount.get()));
            MarkerRenderer.colours(hub.marker, colors, sev, boxSide, boxLine);
            for (Spawner s : en.getValue().values()) {
                if (budget-- <= 0) return;
                if (!passes(s.mob)) continue;
                event.renderer.box(s.x, s.y, s.z, s.x + 1, s.y + 1, s.z + 1, boxSide, boxLine, hub.marker.shapeMode.get(), 0);
            }
        }
    }

    @Override
    protected void cleared() {
        byChunk.clear();
        retries.clear();
        queue.clear();
        queueLoadedChunks(queue);
    }

    @Override
    protected String diag() {
        int spawners = 0;
        for (Map<Long, Spawner> m : byChunk.values()) spawners += m.size();
        return "scanned chunks=" + scanned + " spawners remembered=" + spawners + " in " + byChunk.size() + " chunks, "
            + retries.size() + " waiting to be identified";
    }
}
