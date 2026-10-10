package dev.clicksclient.modules;

import dev.clicksclient.core.Depth;
import dev.clicksclient.core.Flag;
import dev.clicksclient.core.FlickerEngine;
import dev.clicksclient.core.LightEvent;
import dev.clicksclient.core.LightWorld;
import dev.clicksclient.mc.ClicksModule;
import dev.clicksclient.mc.Feature;
import dev.clicksclient.mc.LightService;
import dev.clicksclient.mc.MarkerRenderer;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects movement below Y 0 while you stand on the surface. It only listens to things the server still
 * tells you about even when it hides the terrain: light updates, sounds (which carry coordinates),
 * tracked entities and block changes. Flags fade after the movement stops.
 */
public class MotionScanner extends Feature {
    private final Setting<Integer> surfaceY = num("surface-y",
        "Scanning only runs while you are at or above this Y, which means standing on the surface. Your own digging below it would otherwise show up as motion. -64 scans from anywhere.",
        0, -64, 320, -64, 64, null);

    private final Setting<Integer> linger = num("linger-seconds",
        "How long a spot stays flagged after the last movement seen there.", 25, 3, 600, 120);

    private final Setting<Boolean> useBlocks = bool("use-block-changes",
        "Blocks being broken or placed below Y 0 that the server tells you about.", true);

    private final Setting<Boolean> useLight = bool("use-light",
        "Movement shows up as bursts of light updates (placing torches, lava, lit furnaces, redstone).", true);

    private final Setting<Integer> lightWindow = num("light-window-seconds",
        "Time window the update count is measured over.", 6, 2, 30, 30, () -> useLight.get());

    private final Setting<Integer> lightMin = num("light-min-updates",
        "Light updates for one section needed inside the window.", 3, 1, 100, 30, () -> useLight.get());

    private final Setting<Integer> lightGrace = num("chunk-grace-seconds",
        "Ignore light updates this long after a chunk arrives. The server sends a burst while it lights new chunks.",
        8, 0, 120, 60, () -> useLight.get());

    private final Setting<Boolean> useSounds = bool("use-sounds",
        "Sounds carry exact coordinates, so footsteps, digging and building give away where something is. Only reaches as far as the server sends sound.", true);

    private final Setting<Boolean> soundPlayers = bool("player-sounds",
        "Footsteps and other player noises.", true, () -> useSounds.get());

    private final Setting<Boolean> soundBlocks = bool("block-sounds",
        "Blocks being broken, placed or interacted with.", true, () -> useSounds.get());

    private final Setting<Boolean> soundMobs = bool("mob-sounds",
        "Mob noises. Noisy, because caves are full of mobs.", false, () -> useSounds.get());

    private final Setting<Boolean> useEntities = bool("use-entities",
        "Watch entities the server is tracking for you and flag the ones that move below Y 0.", true);

    private final Setting<Boolean> entityPlayers = bool("track-players",
        "Flag moving players.", true, () -> useEntities.get());

    private final Setting<Boolean> entityMobs = bool("track-mobs",
        "Flag moving mobs.", false, () -> useEntities.get());

    private final Setting<Double> entityMove = dec("min-movement",
        "Blocks an entity must move between checks to count as moving.", 0.08, 0.01, 2.0, 1.0, () -> useEntities.get());

    private final Setting<Integer> entityInterval = num("check-interval",
        "Ticks between entity checks.", 4, 1, 40, 20, () -> useEntities.get());

    private final Setting<Boolean> pingBoxes = bool("ping-boxes",
        "Draw a box through the walls at the exact spot of each movement.", true);

    private final Setting<Double> pingSize = dec("ping-size",
        "Size of the ping boxes in blocks.", 1.0, 0.2, 6.0, 4.0, () -> pingBoxes.get());

    private final Setting<Boolean> pingFade = bool("ping-fade",
        "Fade pings out as they age. Fresh movement is drawn in the high colour.", true, () -> pingBoxes.get());

    private static final class SoundPing {
        final double x, y, z;
        final String label;

        SoundPing(double x, double y, double z, String label) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.label = label;
        }
    }

    private static final class Ping {
        final double x, y, z;
        long born;

        Ping(double x, double y, double z, long born) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.born = born;
        }
    }

    private final ConcurrentLinkedQueue<SoundPing> sounds = new ConcurrentLinkedQueue<>();
    private final AtomicInteger soundCount = new AtomicInteger();
    private final List<Ping> pings = new ArrayList<>();
    private final FlickerEngine engine = new FlickerEngine();
    private final FlickerEngine.Params lightParams = new FlickerEngine.Params();
    private final Color pingSide = new Color(0, 0, 0, 0);
    private final Color pingLine = new Color(0, 0, 0, 0);
    private Map<Integer, double[]> lastPos = new HashMap<>();
    private int entityTicks;
    private int sweepTicks;
    private long soundsSeen, soundPings, entityPings, lightPings, blockPings;

    public MotionScanner(ClicksModule hub) {
        super(hub, "Motion Scanner", "motion-",
            "Detects movement below Y 0 while you stand on the surface, using light, sound, entity and block-change data the server still sends.");
        addColors(
            new SettingColor(255, 60, 160, 40), new SettingColor(255, 90, 180, 200),
            new SettingColor(255, 170, 220, 255), new SettingColor(255, 0, 120, 255),
            new SettingColor(255, 90, 180, 200));
    }

    @Override
    protected long expireMs() {
        return linger.get() * 1000L;
    }

    @Override
    protected void activate() {
        lastPos.clear();
    }

    @Override
    protected void deactivate() {
        sounds.clear();
        soundCount.set(0);
        pings.clear();
        lastPos.clear();
    }

    @Override
    protected boolean usesLight() {
        return useLight.get();
    }

    @Override
    protected int lightPauseY() {
        return surfaceY.get() <= -64 ? Integer.MIN_VALUE : surfaceY.get();
    }

    /** True while you are too deep to scan, which is the whole point: it is meant to be used from the surface. */
    private boolean paused() {
        return mc.player == null || (surfaceY.get() > -64 && mc.player.getY() < surfaceY.get());
    }

    // ------------------------------------------------------------ sources

    /** Network thread: only queue things up. */
    @Override
    protected void packet(PacketEvent.Receive event) {
        if (!useSounds.get()) return;
        if (!(event.packet instanceof PlaySoundS2CPacket s)) return;
        double y = s.getY();
        if (y > ceiling() + 1.0) return;
        SoundCategory cat = s.getCategory();
        String label;
        if (cat == SoundCategory.PLAYERS && soundPlayers.get()) label = "player noise";
        else if (cat == SoundCategory.BLOCKS && soundBlocks.get()) label = "digging or building noise";
        else if ((cat == SoundCategory.HOSTILE || cat == SoundCategory.NEUTRAL) && soundMobs.get()) label = "mob noise";
        else return;
        if (soundCount.get() >= 512) return;
        sounds.add(new SoundPing(s.getX(), y, s.getZ(), label));
        soundCount.incrementAndGet();
    }

    @Override
    protected void blockUpdate(BlockUpdateEvent event) {
        if (!useBlocks.get() || !ready() || paused()) return;
        BlockPos p = event.pos;
        if (p.getY() > ceiling()) return;
        if (event.oldState.getBlock() == event.newState.getBlock()) return; // a real break or placement, not a state flip
        blockPings++;
        addPing(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, "block change");
    }

    @Override
    protected void light(LightWorld.Chunk c, LightEvent e) {
        if (e.full || paused()) return;
        if (e.nowMs - c.loadedAtMs < lightGrace.get() * 1000L) return;
        int ceiling = ceiling();
        int nowSec = (int) (e.nowMs / 1000L);
        lightParams.windowSec = lightWindow.get();
        lightParams.minLight = lightMin.get();
        lightParams.minLightActive = 1;
        for (int i : c.changedIndexes(e, ceiling, true, true)) {
            FlickerEngine.Hit h = engine.onLight(c.key, i, c.sectionCount + 2, nowSec, lightParams);
            if (h == null) continue;
            int secY = Depth.sectionY(c.bottomSectionY, i);
            lightPings++;
            addPing((c.x << 4) + 8, Math.min((secY << 4) + 8, ceiling), (c.z << 4) + 8, "light movement, section Y " + secY);
        }
    }

    @Override
    protected void unload(long key) {
        engine.dropChunk(key);
    }

    @Override
    protected void reset() {
        engine.clear();
    }

    private void scanEntities() {
        Map<Integer, double[]> next = new HashMap<>();
        double min = entityMove.get();
        double min2 = min * min;
        int ceiling = ceiling();
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            if (e.getY() > ceiling) continue;
            boolean player = e instanceof PlayerEntity;
            if (player ? !entityPlayers.get() : !entityMobs.get()) continue;
            if (!player && !(e instanceof LivingEntity)) continue;
            double x = e.getX(), y = e.getY(), z = e.getZ();
            next.put(e.getId(), new double[]{x, y, z});
            double[] prev = lastPos.get(e.getId());
            if (prev == null) continue;
            double dx = x - prev[0], dy = y - prev[1], dz = z - prev[2];
            if (dx * dx + dy * dy + dz * dz < min2) continue;
            entityPings++;
            addPing(x, y + 0.9, z, player ? "player moving: " + e.getName().getString() : e.getType().getName().getString() + " moving");
        }
        lastPos = next;
    }

    // ------------------------------------------------------------ pings

    private void addPing(double x, double y, double z, String label) {
        if (!ready() || y > ceiling() + 1.0) return;
        long now = System.currentTimeMillis();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int by = Math.min((int) Math.floor(y), ceiling());
        int cx = bx >> 4, cz = bz >> 4;

        boolean dup = false;
        if (!pings.isEmpty()) {
            Ping last = pings.get(pings.size() - 1);
            double dx = last.x - x, dy = last.y - y, dz = last.z - z;
            if (now - last.born < 300 && dx * dx + dy * dy + dz * dz < 2.25) {
                last.born = now;
                dup = true;
            }
        }
        if (!dup) {
            pings.add(new Ping(x, y, z, now));
            if (pings.size() > 600) pings.remove(0);
        }

        Flag existing = flags.get(Depth.chunkKey(cx, cz));
        int hits = existing == null ? 0 : existing.hits;
        flag(cx, cz, bx, by, bz, Math.min(1f, (hits + 1) / 12f), label);
    }

    @Override
    protected void tick() {
        if (!ready()) return;
        pauseHint(paused(), surfaceY.get());
        long now = System.currentTimeMillis();

        // sounds arrive on the network thread; hand them over here
        SoundPing sp;
        boolean off = paused();
        while ((sp = sounds.poll()) != null) {
            soundCount.decrementAndGet();
            if (off) continue;
            soundsSeen++;
            soundPings++;
            addPing(sp.x, sp.y, sp.z, sp.label);
        }

        if (!off && useEntities.get() && ++entityTicks >= entityInterval.get()) {
            entityTicks = 0;
            scanEntities();
        } else if (off) {
            lastPos.clear();
        }

        long cutoff = now - linger.get() * 1000L;
        pings.removeIf(p -> p.born < cutoff);

        if (++sweepTicks >= 100) {
            sweepTicks = 0;
            engine.sweep((int) (now / 1000L), lightWindow.get());
        }
    }

    @Override
    protected void render(Render3DEvent event) {
        renderFlags(event);
        if (!pingBoxes.get() || pings.isEmpty()) return;
        long now = System.currentTimeMillis();
        float span = Math.max(1000f, linger.get() * 1000f);
        double half = pingSize.get() / 2.0;
        for (Ping p : pings) {
            float life = Math.max(0f, 1f - (now - p.born) / span);
            MarkerRenderer.colours(hub.marker, colors, life, pingSide, pingLine);
            if (pingFade.get()) {
                pingSide.set(pingSide.r, pingSide.g, pingSide.b, (int) (pingSide.a * life));
                pingLine.set(pingLine.r, pingLine.g, pingLine.b, (int) (pingLine.a * life));
            }
            event.renderer.box(p.x - half, p.y - half, p.z - half, p.x + half, p.y + half, p.z + half,
                pingSide, pingLine, hub.marker.shapeMode.get(), 0);
        }
    }

    @Override
    protected void cleared() {
        pings.clear();
        engine.clear();
        lastPos.clear();
    }

    @Override
    protected String diag() {
        return (ready() && paused() ? "[PAUSED: go to the surface, you are below Y " + surfaceY.get() + "] " : "")
            + "pings sound=" + soundPings + " entity=" + entityPings + " light=" + lightPings + " block=" + blockPings
            + " | live pings=" + pings.size() + " | " + LightService.INSTANCE.diag();
    }
}
