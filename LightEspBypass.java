package dev.clicksclient.modules;

import dev.clicksclient.core.Depth;
import dev.clicksclient.core.LightEvent;
import dev.clicksclient.core.LightSourceFinder;
import dev.clicksclient.core.LightWorld;
import dev.clicksclient.mc.ClicksModule;
import dev.clicksclient.mc.Feature;
import dev.clicksclient.mc.LightService;
import dev.clicksclient.mc.MarkerRenderer;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Reads the block-light arrays out of the server's light packets and shows every light source below Y 0.
 * The server can disguise blocks, but the light they cast is still in the packets.
 */
public class LightEspBypass extends Feature {
    private final Setting<Integer> minLevel = num("min-level",
        "Ignore light sources dimmer than this. Torch 14, lantern 15, lava 15, soul torch 10.", 8, 1, 15, 15);

    private final Setting<Integer> maxCells = num("max-source-size",
        "Ignore merged light areas bigger than this many blocks. Filters out big natural lava lakes.", 48, 1, 4096, 512);

    private final Setting<Integer> minSources = num("min-sources",
        "Flag a chunk once it holds at least this many light sources.", 1, 1, 64, 16);

    private final Setting<Boolean> sourceBoxes = bool("source-boxes",
        "Draw a box through the walls on every light source.", true);

    private final Setting<Integer> maxBoxes = num("max-boxes",
        "Most source boxes drawn at once. The nearest win.", 300, 10, 5000, 1000, () -> sourceBoxes.get());

    private final Setting<Integer> scansPerTick = num("chunks-per-tick",
        "How many chunks are analysed each tick. Lower this if you ever see a stutter while loading new areas.", 6, 1, 64, 32);

    private final Setting<Integer> pauseY = num("pause-below-y",
        "Ignore light data while you are below this Y, because your own torches and digging would show up. -64 never pauses.",
        0, -64, 320, -64, 64, null);

    private final Map<Long, List<LightSourceFinder.Source>> sources = new HashMap<>();
    private final LinkedHashSet<Long> dirty = new LinkedHashSet<>();
    private final List<LightSourceFinder.Source> candidates = new ArrayList<>();
    private final Color boxSide = new Color(0, 0, 0, 0);
    private final Color boxLine = new Color(0, 0, 0, 0);
    private long analysed;
    private long sourcesFound;

    public LightEspBypass(ClicksModule hub) {
        super(hub, "Light ESP Bypass", "light-",
            "Shows light sources below Y 0 straight from the server's light packets, even when the blocks themselves are hidden.");
        addColors(
            new SettingColor(255, 200, 0, 40), new SettingColor(255, 220, 0, 200),
            new SettingColor(255, 230, 80, 255), new SettingColor(255, 90, 0, 255),
            new SettingColor(255, 210, 0, 200));
    }

    @Override
    protected void deactivate() {
        dirty.clear();
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
        boolean any = e.full;
        if (!any) {
            for (int i = 0; i < e.block.length; i++) {
                if (e.block[i] != null) {
                    any = true;
                    break;
                }
            }
        }
        if (!any) return;
        markDirty(c.x, c.z);
        // light spills across chunk borders, so the neighbours may change what counts as a peak
        markDirty(c.x + 1, c.z);
        markDirty(c.x - 1, c.z);
        markDirty(c.x, c.z + 1);
        markDirty(c.x, c.z - 1);
    }

    @Override
    protected void unload(long key) {
        dirty.remove(key);
        sources.remove(key);
    }

    @Override
    protected void reset() {
        dirty.clear();
        sources.clear();
    }

    private void markDirty(int cx, int cz) {
        if (LightService.INSTANCE.world.get(cx, cz) != null) dirty.add(Depth.chunkKey(cx, cz));
    }

    @Override
    protected void tick() {
        if (!ready()) return;
        pauseHint(paused(), pauseY.get());
        if (dirty.isEmpty()) return;
        int n = scansPerTick.get();
        Iterator<Long> it = dirty.iterator();
        while (n-- > 0 && it.hasNext()) {
            long key = it.next();
            it.remove();
            analyse(key);
        }
    }

    private void analyse(long key) {
        LightWorld lw = LightService.INSTANCE.world;
        LightWorld.Chunk c = lw.get(key);
        if (c == null) {
            sources.remove(key);
            return;
        }
        List<LightSourceFinder.Source> list = LightSourceFinder.find(lw, c, ceiling(), minLevel.get(), maxCells.get(), 64);
        analysed++;
        if (list.isEmpty()) {
            sources.remove(key);
            flags.remove(key); // the light is gone, so is the flag
            return;
        }
        sources.put(key, list);
        sourcesFound += list.size();
        if (list.size() < minSources.get()) return;
        LightSourceFinder.Source top = list.get(0);
        float severity = Math.min(1f, list.size() / 6f);
        flag(c.x, c.z, (int) Math.floor(top.centerX()), (int) Math.floor(top.centerY()), (int) Math.floor(top.centerZ()),
            severity, list.size() + " light source(s), brightest level " + top.level);
    }

    @Override
    protected void render(Render3DEvent event) {
        renderFlags(event);
        if (!sourceBoxes.get() || sources.isEmpty() || mc.player == null) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int pcx = ((int) Math.floor(px)) >> 4;
        int pcz = ((int) Math.floor(pz)) >> 4;
        int range = hub.marker.renderDistance.get();

        candidates.clear();
        for (Map.Entry<Long, List<LightSourceFinder.Source>> en : sources.entrySet()) {
            if (Math.abs(Depth.keyX(en.getKey()) - pcx) > range || Math.abs(Depth.keyZ(en.getKey()) - pcz) > range) continue;
            for (LightSourceFinder.Source s : en.getValue()) {
                double dx = s.centerX() - px, dy = s.centerY() - py, dz = s.centerZ() - pz;
                s.scratch = dx * dx + dy * dy + dz * dz;
                candidates.add(s);
            }
        }
        int limit = maxBoxes.get();
        if (candidates.size() > limit) candidates.sort(Comparator.comparingDouble(s -> s.scratch));
        int count = Math.min(limit, candidates.size());
        int lo = minLevel.get();
        for (int i = 0; i < count; i++) {
            LightSourceFinder.Source s = candidates.get(i);
            float sev = (s.level - lo) / (float) Math.max(1, 15 - lo);
            MarkerRenderer.colours(hub.marker, colors, sev, boxSide, boxLine);
            event.renderer.box(s.minX, s.minY, s.minZ, s.maxX + 1, s.maxY + 1, s.maxZ + 1, boxSide, boxLine, hub.marker.shapeMode.get(), 0);
        }
    }

    @Override
    protected void cleared() {
        sources.clear();
        dirty.clear();
    }

    @Override
    protected String diag() {
        int total = 0;
        for (List<LightSourceFinder.Source> l : sources.values()) total += l.size();
        return "analysed chunks=" + analysed + " sources now=" + total + " in " + sources.size() + " chunks (total found " + sourcesFound + ")"
            + (paused() ? " [paused: you are below Y " + pauseY.get() + "]" : "")
            + " | " + LightService.INSTANCE.diag();
    }
}
