package dev.clicksclient.mc;

import dev.clicksclient.ClicksClient;
import dev.clicksclient.core.Flag;
import dev.clicksclient.core.LightEvent;
import dev.clicksclient.core.LightWorld;
import dev.clicksclient.modules.AntiEspBypass;
import dev.clicksclient.modules.HugeAreaDetector;
import dev.clicksclient.modules.LightEspBypass;
import dev.clicksclient.modules.MonsterSpawnerDetector;
import dev.clicksclient.modules.MotionScanner;
import dev.clicksclient.modules.RedstoneInspector;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * The one Meteor module. Its settings window holds a section per feature plus the shared Markers, Tracers,
 * Alerts and Diagnostics sections. It also owns every event subscription and hands events to the features
 * that are switched on, so there is a single place to turn everything on or off.
 */
public class ClicksModule extends Module implements LightService.Client {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> ceilingY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest Y every feature looks at. It stops at -1, so nothing at or above Y 0 is ever reported.")
        .defaultValue(-1)
        .range(-64, -1)
        .sliderRange(-64, -1)
        .build());

    public final List<Feature> features = new ArrayList<>();
    public MarkerOptions marker;
    public Setting<Integer> maxFlags;
    public Setting<Integer> expireMinutes;

    private Setting<Boolean> chatAlert;
    private Setting<Boolean> showCoords;
    private Setting<Integer> alertCooldown;
    private Setting<Boolean> clearFlags;
    private Setting<Boolean> diagnostics;

    private ClientWorld lastWorld;
    private long lastAlertMs;
    private long lastDiagMs;
    private int pruneTicks;
    private int farTicks;
    private boolean clearPending;
    private boolean lightAcquired;

    public ClicksModule() {
        super(ClicksClient.CATEGORY, "clicks-client",
            "Click's Client: light ESP, anti-ESP, redstone, spawner, huge area and motion detection below Y 0, with flagged chunks marked above the surface.");

        features.add(new LightEspBypass(this));
        features.add(new AntiEspBypass(this));
        features.add(new RedstoneInspector(this));
        features.add(new HugeAreaDetector(this));
        features.add(new MonsterSpawnerDetector(this));
        features.add(new MotionScanner(this));

        marker = new MarkerOptions(settings);

        SettingGroup a = settings.createGroup("Alerts");
        chatAlert = a.add(new BoolSetting.Builder()
            .name("chat-alert")
            .description("Print a chat message the first time a chunk is flagged.")
            .defaultValue(true)
            .build());
        showCoords = a.add(new BoolSetting.Builder()
            .name("show-coordinates")
            .description("Include coordinates in the chat message.")
            .defaultValue(true)
            .visible(() -> chatAlert.get())
            .build());
        alertCooldown = a.add(new IntSetting.Builder()
            .name("alert-cooldown")
            .description("Minimum seconds between chat messages, so a burst of flags does not flood chat.")
            .defaultValue(1)
            .range(0, 60)
            .sliderRange(0, 30)
            .visible(() -> chatAlert.get())
            .build());
        maxFlags = a.add(new IntSetting.Builder()
            .name("max-flags")
            .description("Most flagged chunks each feature keeps at once. The oldest are dropped first.")
            .defaultValue(1024)
            .range(16, 20000)
            .sliderRange(16, 4096)
            .build());
        expireMinutes = a.add(new IntSetting.Builder()
            .name("expire-minutes")
            .description("Forget a flagged chunk this many minutes after it was last seen. 0 keeps it until you clear it. Motion Scanner has its own, shorter, timer.")
            .defaultValue(0)
            .range(0, 1440)
            .sliderRange(0, 120)
            .build());
        clearFlags = a.add(new BoolSetting.Builder()
            .name("clear-flags")
            .description("Switch on to clear every flagged chunk in every feature. It switches itself back off.")
            .defaultValue(false)
            .onChanged(v -> {
                if (v) clearPending = true;
            })
            .build());

        SettingGroup d = settings.createGroup("Diagnostics");
        diagnostics = d.add(new BoolSetting.Builder()
            .name("diagnostics")
            .description("Print a status line per feature every 15 seconds showing what the server is actually sending. Handy for checking a signal exists.")
            .defaultValue(false)
            .build());
    }

    /** Used by features to create their section of the settings window. */
    public SettingGroup newGroup(String name) {
        return settings.createGroup(name);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onActivate() {
        // tick() switches the enabled features on. Remembered data is only dropped when the world really changes,
        // so toggling the module off and on keeps spawners and reveals you already found.
        for (Feature f : features) f.running = false;
    }

    @Override
    public void onDeactivate() {
        for (Feature f : features) {
            if (f.running) {
                f.running = false;
                f.deactivate();
            }
        }
        if (lightAcquired) {
            LightService.INSTANCE.release(this);
            lightAcquired = false;
        }
        FarPlane.report(this, 0f);
    }

    /** Starts and stops features as their Enabled switches change, and the light tap with them. */
    private void sync() {
        boolean needLight = false;
        for (Feature f : features) {
            boolean want = f.enabled.get();
            if (want && !f.running) {
                f.running = true;
                f.activate();
            } else if (!want && f.running) {
                f.running = false;
                f.deactivate();
            }
            if (f.running && f.usesLight()) needLight = true;
        }
        if (needLight && !lightAcquired) {
            LightService.INSTANCE.acquire(this);
            lightAcquired = true;
        } else if (!needLight && lightAcquired) {
            LightService.INSTANCE.release(this);
            lightAcquired = false;
        }
    }

    // ------------------------------------------------------------ events

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            for (Feature f : features) f.worldChanged();
        }
        sync();

        String wk = mc.world.getRegistryKey().getValue().toString();
        long now = System.currentTimeMillis();
        for (Feature f : features) f.flags.selectWorld(wk);

        if (clearPending) {
            clearPending = false;
            clearAll();
            clearFlags.set(false);
        }
        if (++pruneTicks >= 20) {
            pruneTicks = 0;
            for (Feature f : features) {
                f.flags.prune(now, f.expireMs(), maxFlags.get());
                f.flags.pruneTests(now, 15_000L);
            }
        }
        if (++farTicks >= 10) {
            farTicks = 0;
            updateFarPlane();
        }
        if (diagnostics.get() && now - lastDiagMs >= 15_000L) {
            lastDiagMs = now;
            for (Feature f : features) {
                if (f.running) ChatUtils.info("(%s) %s", f.title, f.diag());
            }
        }
        for (Feature f : features) {
            if (f.running) f.tick();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        for (Feature f : features) {
            if (f.running) f.render(event);
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        for (Feature f : features) {
            if (f.running) f.blockUpdate(event);
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        for (Feature f : features) {
            if (f.running) f.chunkData(event);
        }
    }

    /** Network thread. */
    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        for (Feature f : features) {
            if (f.running) f.packet(event);
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        for (Feature f : features) {
            f.flags.clearAll();
            f.worldChanged();
        }
        lastWorld = null;
    }

    // ------------------------------------------------------------ light service client

    @Override
    public void onLight(LightWorld.Chunk c, LightEvent e) {
        for (Feature f : features) {
            if (f.running && f.usesLight()) f.light(c, e);
        }
    }

    @Override
    public void onUnload(long key) {
        for (Feature f : features) {
            if (f.running && f.usesLight()) f.unload(key);
        }
    }

    @Override
    public void onReset() {
        for (Feature f : features) {
            if (f.running && f.usesLight()) f.reset();
        }
    }

    /** The light tap only pauses when every light-using feature wants it to. */
    @Override
    public int pauseBelowY() {
        int min = Integer.MAX_VALUE;
        boolean any = false;
        for (Feature f : features) {
            if (!f.running || !f.usesLight()) continue;
            int th = f.lightPauseY();
            if (th == Integer.MIN_VALUE) return Integer.MIN_VALUE;
            any = true;
            min = Math.min(min, th);
        }
        return any ? min : Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------ shared behaviour

    private void updateFarPlane() {
        if (!isActive() || !marker.show.get() || !marker.extendFarPlane.get()) {
            FarPlane.report(this, 0f);
            return;
        }
        double px = mc.player.getX(), pz = mc.player.getZ();
        int pcx = ((int) Math.floor(px)) >> 4;
        int pcz = ((int) Math.floor(pz)) >> 4;
        int range = marker.renderDistance.get();
        double max = 0;
        for (Feature f : features) {
            if (!f.running) continue;
            for (Flag fl : f.flags.values()) {
                if (Math.abs(fl.chunkX - pcx) > range || Math.abs(fl.chunkZ - pcz) > range) continue;
                double d = Math.hypot((fl.chunkX << 4) + 8 - px, (fl.chunkZ << 4) + 8 - pz);
                if (d > max) max = d;
            }
        }
        FarPlane.report(this, max <= 0 ? 0f : (float) Math.min(max + 256.0, marker.farPlaneLimit.get()));
    }

    public void alert(String who, String label, int x, int y, int z) {
        if (!chatAlert.get()) return;
        long now = System.currentTimeMillis();
        if (now - lastAlertMs < alertCooldown.get() * 1000L) return;
        lastAlertMs = now;
        if (showCoords.get()) ChatUtils.warning("(%s) %s at %d, %d, %d", who, label, x, y, z);
        else ChatUtils.warning("(%s) %s", who, label);
    }

    public void clearAll() {
        for (Feature f : features) f.clearAll();
    }

    /** Puts a test flag on your chunk for every feature that is on. Returns how many. */
    public int injectTest() {
        int n = 0;
        for (Feature f : features) {
            if (!f.running) continue;
            f.injectTest();
            n++;
        }
        return n;
    }

    @Override
    public String getInfoString() {
        int total = 0;
        for (Feature f : features) total += f.flags.size();
        return total == 0 ? null : Integer.toString(total);
    }
}
