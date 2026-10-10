package dev.clicksclient.mc;

import dev.clicksclient.core.Depth;
import dev.clicksclient.core.LightEvent;
import dev.clicksclient.core.LightWorld;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Taps the light data in chunk and light-update packets (network thread), reduces it to small events,
 * and feeds a LightWorld on the main thread. Only subscribed to the event bus while a module needs it.
 */
public final class LightService {
    public static final LightService INSTANCE = new LightService();

    /** A module that consumes light data. */
    public interface Client extends LightWorld.Listener {
        /** Light is ignored while the player is below this Y (their own digging pollutes it). Integer.MIN_VALUE = never pause. */
        int pauseBelowY();
    }

    private static final int MAX_QUEUE = 8192;
    private static final int DRAIN_PER_TICK = 1024;

    public final LightWorld world = new LightWorld();

    private final ConcurrentLinkedQueue<LightEvent> events = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> unloads = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final Set<Long> seenChunks = ConcurrentHashMap.newKeySet();
    private final List<Client> clients = new ArrayList<>();
    private ClientWorld lastWorld;
    private boolean subscribed;

    // diagnostics
    public final AtomicLong chunkFirst = new AtomicLong();
    public final AtomicLong chunkResent = new AtomicLong();
    public final AtomicLong lightUpdates = new AtomicLong();
    public final AtomicLong unloaded = new AtomicLong();
    public final AtomicLong dropped = new AtomicLong();
    public final AtomicLong paused = new AtomicLong();
    public final AtomicLong errors = new AtomicLong();

    private LightService() {}

    public void acquire(Client c) {
        if (clients.contains(c)) return;
        clients.add(c);
        world.addListener(c);
        if (!subscribed) {
            MeteorClient.EVENT_BUS.subscribe(this);
            subscribed = true;
        }
    }

    public void release(Client c) {
        clients.remove(c);
        world.removeListener(c);
        if (clients.isEmpty() && subscribed) {
            MeteorClient.EVENT_BUS.unsubscribe(this);
            subscribed = false;
            events.clear();
            unloads.clear();
            queued.set(0);
            seenChunks.clear();
            world.clear();
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        try {
            Packet<?> p = event.packet;
            if (p instanceof ChunkDataS2CPacket c) {
                int x = c.getChunkX(), z = c.getChunkZ();
                if (seenChunks.add(Depth.chunkKey(x, z))) chunkFirst.incrementAndGet();
                else chunkResent.incrementAndGet(); // the server sent a chunk we already hold
                offer(x, z, true, c.getLightData());
            } else if (p instanceof LightUpdateS2CPacket l) {
                lightUpdates.incrementAndGet();
                offer(l.getChunkX(), l.getChunkZ(), false, l.getData());
            } else if (p instanceof UnloadChunkS2CPacket u) {
                long key = Depth.chunkKey(u.pos().x, u.pos().z);
                seenChunks.remove(key);
                unloads.add(key);
                unloaded.incrementAndGet();
            }
        } catch (Throwable t) {
            errors.incrementAndGet();
        }
    }

    private void offer(int cx, int cz, boolean full, LightData d) {
        if (d == null) return;
        if (queued.get() >= MAX_QUEUE) {
            dropped.incrementAndGet();
            return;
        }
        LightEvent e = LightEvent.build(cx, cz, full,
            d.getInitedSky(), d.getUninitedSky(), d.getSkyNibbles(),
            d.getInitedBlock(), d.getUninitedBlock(), d.getBlockNibbles(),
            System.currentTimeMillis());
        events.add(e);
        queued.incrementAndGet();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld w = mc.world;
        if (w != lastWorld) {
            lastWorld = w;
            world.clear();
            events.clear();
            unloads.clear();
            queued.set(0);
            seenChunks.clear();
        }
        if (w == null || mc.player == null) return;

        Long key;
        while ((key = unloads.poll()) != null) world.unload(key);

        boolean pause = !clients.isEmpty();
        double py = mc.player.getY();
        for (Client c : clients) {
            int th = c.pauseBelowY();
            if (th == Integer.MIN_VALUE || py >= th) {
                pause = false;
                break;
            }
        }

        int bottom = w.getBottomSectionCoord();
        int count = w.countVerticalSections();
        int budget = DRAIN_PER_TICK;
        LightEvent e;
        while (budget-- > 0 && (e = events.poll()) != null) {
            queued.decrementAndGet();
            if (pause) {
                paused.incrementAndGet();
                continue;
            }
            world.apply(e, bottom, count);
        }
    }

    public String diag() {
        int[] h = world.histogram(Depth.CEILING);
        return "chunk packets first=" + chunkFirst.get() + " resent=" + chunkResent.get()
            + " | light updates=" + lightUpdates.get() + " unloads=" + unloaded.get()
            + " | dropped=" + dropped.get() + " paused=" + paused.get() + " errors=" + errors.get()
            + " | chunks held=" + h[11]
            + " | below Y 0 sky: empty=" + h[1] + " zeroed=" + h[2] + " normal=" + h[3] + " uniform=" + h[4]
            + " | below Y 0 block light: empty=" + h[6] + " zeroed=" + h[7] + " lit=" + (h[8] + h[9]) + " brightest=" + h[10];
    }
}
