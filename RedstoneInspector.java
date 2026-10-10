package dev.clicksclient.modules;

import dev.clicksclient.core.Depth;
import dev.clicksclient.core.FlickerEngine;
import dev.clicksclient.core.LightEvent;
import dev.clicksclient.core.LightWorld;
import dev.clicksclient.mc.ClicksModule;
import dev.clicksclient.mc.Feature;
import dev.clicksclient.mc.LightService;
import dev.clicksclient.mc.RedstoneStates;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Catches redstone that keeps switching on and off below Y 0: clocks, lamp flashers, farms.
 *
 * Signal 1: block states flipping (lit, powered, extended...), which gives the exact block.
 * Signal 2: the server sending light updates for the same section again and again, which works even
 * when the block changes themselves are not sent to you.
 */
public class RedstoneInspector extends Feature {
    private final Setting<List<Block>> tracked = blocks("tracked-blocks",
        "Blocks whose on/off flips are counted.",
        new ArrayList<>(List.of(
            Blocks.REDSTONE_LAMP, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH, Blocks.REPEATER, Blocks.COMPARATOR,
            Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.DISPENSER, Blocks.DROPPER, Blocks.REDSTONE_WIRE,
            Blocks.COPPER_BULB, Blocks.EXPOSED_COPPER_BULB, Blocks.WEATHERED_COPPER_BULB, Blocks.OXIDIZED_COPPER_BULB,
            Blocks.WAXED_COPPER_BULB, Blocks.WAXED_EXPOSED_COPPER_BULB, Blocks.WAXED_WEATHERED_COPPER_BULB,
            Blocks.WAXED_OXIDIZED_COPPER_BULB)));

    private final Setting<Integer> window = num("window-seconds",
        "Length of the time window the counts below are measured over.", 10, 3, 30, 30);

    private final Setting<Integer> minToggles = num("min-toggles",
        "A block must flip at least this many times inside the window.", 6, 2, 200, 60);

    private final Setting<Integer> minActive = num("min-active-seconds",
        "...spread over at least this many separate seconds. Stops one lever being mashed from counting as a clock.", 3, 1, 10, 10);

    private final Setting<Boolean> useLight = bool("use-light-signal",
        "Also flag chunks whose light keeps updating. Works even when the server does not send you the block changes.", true);

    private final Setting<Integer> minLight = num("min-light-updates",
        "Light updates for one section needed inside the window.", 8, 2, 300, 100, () -> useLight.get());

    private final Setting<Integer> minLightActive = num("min-light-active-seconds",
        "...spread over at least this many separate seconds.", 4, 1, 10, 10, () -> useLight.get());

    private final Setting<Integer> grace = num("chunk-grace-seconds",
        "Ignore light updates this long after a chunk arrives. The server sends a burst while it finishes lighting new chunks.",
        8, 0, 120, 60, () -> useLight.get());

    private final Setting<Integer> pauseY = num("pause-below-y",
        "Ignore light updates while you are below this Y, because your own torches and digging would count. -64 never pauses.",
        0, -64, 320, -64, 64, () -> useLight.get());

    private final FlickerEngine engine = new FlickerEngine();
    private final FlickerEngine.Params params = new FlickerEngine.Params();
    private int sweepTicks;
    private long flipsSeen;
    private long lightSeen;
    private long hits;

    public RedstoneInspector(ClicksModule hub) {
        super(hub, "Redstone Inspector", "redstone-",
            "Flags chunks below Y 0 where redstone keeps flickering on and off: clocks, lamp flashers and farms.");
        addColors(
            new SettingColor(255, 40, 40, 40), new SettingColor(255, 70, 70, 200),
            new SettingColor(255, 160, 60, 255), new SettingColor(255, 0, 0, 255),
            new SettingColor(255, 70, 70, 200));
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

    private FlickerEngine.Params params() {
        params.windowSec = window.get();
        params.minToggles = minToggles.get();
        params.minActive = minActive.get();
        params.minLight = minLight.get();
        params.minLightActive = minLightActive.get();
        return params;
    }

    private float severity(int count, int minimum) {
        return Math.min(1f, (count - minimum) / (float) Math.max(1, minimum * 3));
    }

    @Override
    protected void blockUpdate(BlockUpdateEvent event) {
        BlockPos p = event.pos;
        if (p.getY() > ceiling()) return;
        Block b = event.newState.getBlock();
        if (event.oldState.getBlock() != b) return;
        if (!tracked.get().contains(b)) return;
        if (!RedstoneStates.flipped(event.oldState, event.newState)) return;

        flipsSeen++;
        int cx = p.getX() >> 4, cz = p.getZ() >> 4;
        int nowSec = (int) (System.currentTimeMillis() / 1000L);
        FlickerEngine.Hit h = engine.onFlip(p.asLong(), Depth.chunkKey(cx, cz), nowSec, params());
        if (h == null) return;
        hits++;
        flag(cx, cz, p.getX(), p.getY(), p.getZ(), severity(h.count, minToggles.get()),
            b.getName().getString() + " flickering, " + h.count + " toggles in " + window.get() + "s");
    }

    @Override
    protected void light(LightWorld.Chunk c, LightEvent e) {
        if (e.full || paused()) return;
        if (e.nowMs - c.loadedAtMs < grace.get() * 1000L) return;
        int ceiling = ceiling();
        int nowSec = (int) (e.nowMs / 1000L);
        for (int i : c.changedIndexes(e, ceiling, false, true)) {
            lightSeen++;
            FlickerEngine.Hit h = engine.onLight(c.key, i, c.sectionCount + 2, nowSec, params());
            if (h == null) continue;
            hits++;
            int secY = Depth.sectionY(c.bottomSectionY, i);
            int ay = Math.min((secY << 4) + 8, ceiling);
            flag(c.x, c.z, (c.x << 4) + 8, ay, (c.z << 4) + 8, severity(h.count, minLight.get()),
                "light flicker, " + h.count + " updates in " + window.get() + "s (section Y " + secY + ")");
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

    @Override
    protected void tick() {
        if (!ready()) return;
        pauseHint(useLight.get() && paused(), pauseY.get());
        if (++sweepTicks >= 100) {
            sweepTicks = 0;
            engine.sweep((int) (System.currentTimeMillis() / 1000L), window.get());
        }
    }

    @Override
    protected void render(Render3DEvent event) {
        renderFlags(event);
    }

    @Override
    protected void cleared() {
        engine.clear();
    }

    @Override
    protected String diag() {
        return "block flips seen=" + flipsSeen + " light updates counted=" + lightSeen + " hits=" + hits
            + " tracking " + engine.trackedBlocks() + " blocks / " + engine.trackedChunks() + " chunks"
            + (useLight.get() && paused() ? " [light paused: you are below Y " + pauseY.get() + "]" : "")
            + " | " + LightService.INSTANCE.diag();
    }
}
