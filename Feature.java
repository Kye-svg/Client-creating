package dev.clicksclient.mc;

import dev.clicksclient.core.ChunkQueue;
import dev.clicksclient.core.Depth;
import dev.clicksclient.core.Flag;
import dev.clicksclient.core.FlagStore;
import dev.clicksclient.core.LightEvent;
import dev.clicksclient.core.LightWorld;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;

import java.util.List;

/**
 * One detector. Features are not Meteor modules: they live inside the single Click's Client module, each
 * with its own section of settings, its own flagged chunks and its own colours. The module calls the hooks
 * below for every feature that is switched on.
 */
public abstract class Feature {
    protected final ClicksModule hub;
    protected final MinecraftClient mc = MinecraftClient.getInstance();
    public final String title;
    public final FlagStore flags = new FlagStore();
    public final Setting<Boolean> enabled;
    public volatile boolean running;
    public Colors colors;

    private final String key;
    private final SettingGroup group;
    private boolean pauseHinted;

    protected Feature(ClicksModule hub, String title, String key, String description) {
        this.hub = hub;
        this.title = title;
        this.key = key;
        this.group = hub.newGroup(title);
        this.enabled = group.add(new BoolSetting.Builder()
            .name(key + "enabled")
            .description(description)
            .defaultValue(true)
            .build());
    }

    // ------------------------------------------------------------ settings helpers

    protected final Setting<Boolean> bool(String name, String desc, boolean def) {
        return bool(name, desc, def, null);
    }

    protected final Setting<Boolean> bool(String name, String desc, boolean def, IVisible vis) {
        BoolSetting.Builder b = new BoolSetting.Builder().name(key + name).description(desc).defaultValue(def);
        if (vis != null) b.visible(vis);
        return group.add(b.build());
    }

    protected final Setting<Integer> num(String name, String desc, int def, int min, int max, int sliderMax) {
        return num(name, desc, def, min, max, min, sliderMax, null);
    }

    protected final Setting<Integer> num(String name, String desc, int def, int min, int max, int sliderMax, IVisible vis) {
        return num(name, desc, def, min, max, min, sliderMax, vis);
    }

    protected final Setting<Integer> num(String name, String desc, int def, int min, int max, int sliderMin, int sliderMax, IVisible vis) {
        IntSetting.Builder b = new IntSetting.Builder().name(key + name).description(desc).defaultValue(def)
            .range(min, max).sliderRange(sliderMin, sliderMax);
        if (vis != null) b.visible(vis);
        return group.add(b.build());
    }

    protected final Setting<Double> dec(String name, String desc, double def, double min, double max, double sliderMax, IVisible vis) {
        DoubleSetting.Builder b = new DoubleSetting.Builder().name(key + name).description(desc).defaultValue(def)
            .range(min, max).sliderRange(min, sliderMax);
        if (vis != null) b.visible(vis);
        return group.add(b.build());
    }

    protected final Setting<SettingColor> color(String name, String desc, SettingColor def, IVisible vis) {
        ColorSetting.Builder b = new ColorSetting.Builder().name(key + name).description(desc).defaultValue(def);
        if (vis != null) b.visible(vis);
        return group.add(b.build());
    }

    protected final Setting<String> text(String name, String desc, String def) {
        return group.add(new StringSetting.Builder().name(key + name).description(desc).defaultValue(def).build());
    }

    protected final Setting<List<Block>> blocks(String name, String desc, List<Block> def) {
        return group.add(new BlockListSetting.Builder().name(key + name).description(desc).defaultValue(def).build());
    }

    /** Call last in the constructor so the colours sit at the bottom of the feature's section. */
    protected final void addColors(SettingColor side, SettingColor line, SettingColor low, SettingColor high, SettingColor tracer) {
        colors = new Colors(group, key, () -> hub.marker != null && hub.marker.severityColors.get(), side, line, low, high, tracer);
    }

    // ------------------------------------------------------------ hooks (the module calls these while the feature is on)

    /** The feature was just switched on. */
    protected void activate() {}

    /** The feature was just switched off. */
    protected void deactivate() {}

    protected void tick() {}

    protected void render(Render3DEvent event) {}

    protected void blockUpdate(BlockUpdateEvent event) {}

    protected void chunkData(ChunkDataEvent event) {}

    /** Network thread. Do nothing heavy and touch nothing that is not thread-safe. */
    protected void packet(PacketEvent.Receive event) {}

    /** Whether this feature wants the light-packet tap. */
    protected boolean usesLight() {
        return false;
    }

    /** Light is ignored while the player is below this Y. Integer.MIN_VALUE = never. */
    protected int lightPauseY() {
        return Integer.MIN_VALUE;
    }

    protected void light(LightWorld.Chunk c, LightEvent e) {}

    protected void unload(long chunkKey) {}

    /** The light model was reset (new world). */
    protected void reset() {}

    /** Clear everything remembered. Called by the clear button, and when the world changes. */
    protected void cleared() {}

    /** One line of counters for the diagnostics output. */
    protected abstract String diag();

    /** How long a flag lives after it was last seen. 0 = forever. */
    protected long expireMs() {
        return hub.expireMinutes.get() * 60_000L;
    }

    /** The world or server changed: drop remembered state that belongs to the old one. */
    protected void worldChanged() {
        cleared();
    }

    // ------------------------------------------------------------ helpers for subclasses

    protected final int ceiling() {
        return hub.ceilingY.get();
    }

    protected final boolean ready() {
        return mc.world != null && mc.player != null;
    }

    protected final String worldKey() {
        return mc.world.getRegistryKey().getValue().toString();
    }

    /**
     * Flags a chunk without printing anything. Returns true when it was not flagged before. The ground
     * height is recorded so the marker can sit above the surface.
     */
    protected final boolean flagQuiet(int chunkX, int chunkZ, int ax, int ay, int az, float severity, String label) {
        if (!ready()) return false;
        flags.selectWorld(worldKey());
        boolean isNew = flags.flag(chunkX, chunkZ, ax, ay, az, Math.max(0f, Math.min(1f, severity)), label, System.currentTimeMillis());
        Flag f = flags.get(Depth.chunkKey(chunkX, chunkZ));
        if (f != null) {
            int s = Surface.topY(mc.world, chunkX, chunkZ);
            if (s != Integer.MIN_VALUE) f.surfaceY = s;
        }
        return isNew;
    }

    /** Flags a chunk and alerts in chat the first time. */
    protected final boolean flag(int chunkX, int chunkZ, int ax, int ay, int az, float severity, String label) {
        boolean isNew = flagQuiet(chunkX, chunkZ, ax, ay, az, severity, label);
        if (isNew) alert(label, ax, ay, az);
        return isNew;
    }

    protected final void alert(String label, int x, int y, int z) {
        hub.alert(title, label, x, y, z);
    }

    protected final void renderFlags(Render3DEvent event) {
        MarkerRenderer.render(event, flags.values(), hub.marker, colors);
    }

    /** Queues every chunk the client currently holds around the player. */
    protected final void queueLoadedChunks(ChunkQueue queue) {
        if (!ready()) return;
        int view = mc.options.getViewDistance().getValue();
        int pcx = ((int) Math.floor(mc.player.getX())) >> 4;
        int pcz = ((int) Math.floor(mc.player.getZ())) >> 4;
        for (int dx = -view; dx <= view; dx++) {
            for (int dz = -view; dz <= view; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (mc.world.getChunkManager().getWorldChunk(cx, cz) != null) queue.add(Depth.chunkKey(cx, cz));
            }
        }
    }

    /**
     * Light-based signals ignore data while you are below a set Y, because your own torches and digging
     * would show up. Say so once, so a silent feature is never a mystery.
     */
    protected final void pauseHint(boolean paused, int thresholdY) {
        if (!paused) {
            pauseHinted = false;
            return;
        }
        if (pauseHinted) return;
        pauseHinted = true;
        ChatUtils.info("(%s) Light data is paused because you are below Y %d (your own digging and torches would show up). Go to the surface, or set this feature's Y limit to -64 to never pause.", title, thresholdY);
    }

    // ------------------------------------------------------------ used by the module and the command

    public void clearAll() {
        flags.clear();
        cleared();
    }

    /** Puts a short-lived test flag on your chunk so you can check markers, colours and tracers. */
    public void injectTest() {
        if (!ready()) return;
        flags.selectWorld(worldKey());
        int px = (int) Math.floor(mc.player.getX());
        int pz = (int) Math.floor(mc.player.getZ());
        int cx = px >> 4, cz = pz >> 4;
        long key = Depth.chunkKey(cx, cz);
        boolean existed = flags.get(key) != null;
        long now = System.currentTimeMillis();
        flags.flag(cx, cz, px, -32, pz, 1f, "TEST flag", now);
        Flag f = flags.get(key);
        if (f != null) {
            if (!existed) {
                f.test = true;
                f.firstSeen = now;
            }
            int s = Surface.topY(mc.world, cx, cz);
            if (s != Integer.MIN_VALUE) f.surfaceY = s;
        }
    }

    public String diagLine() {
        return title + (running ? " [on] " : " [off] ") + flags.size() + " flagged | " + diag();
    }
}
