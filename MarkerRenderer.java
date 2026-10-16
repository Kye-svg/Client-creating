package dev.clicksclient.mc;

import dev.clicksclient.core.Flag;
import dev.clicksclient.core.Gradient;
import dev.clicksclient.core.MarkerCluster;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Draws flagged chunks: marker above the ground, exact-spot box, and tracers. */
public final class MarkerRenderer {
    private MarkerRenderer() {}

    private static final Color SIDE = new Color(0, 0, 0, 0);
    private static final Color LINE = new Color(0, 0, 0, 0);
    private static final Color TRACER = new Color(0, 0, 0, 0);
    private static final List<Flag> VISIBLE = new ArrayList<>();
    private static final List<MarkerCluster> TRACE = new ArrayList<>();
    private static int frame;

    /** Fills the two colours for a detection strength between 0 and 1. */
    public static void colours(MarkerOptions o, Colors c, float severity, Color side, Color line) {
        SettingColor s = c.side.get();
        SettingColor l = c.line.get();
        if (o.severityColors.get()) {
            SettingColor lo = c.low.get();
            SettingColor hi = c.high.get();
            int r = Gradient.lerp(lo.r, hi.r, severity);
            int g = Gradient.lerp(lo.g, hi.g, severity);
            int b = Gradient.lerp(lo.b, hi.b, severity);
            side.set(r, g, b, s.a);
            line.set(r, g, b, l.a);
        } else {
            side.set(s.r, s.g, s.b, s.a);
            line.set(l.r, l.g, l.b, l.a);
        }
    }

    /** Highest known surface among a cluster's chunks, so a merged marker clears every chunk under it. */
    private static double markerY(MarkerCluster cl, MarkerOptions o, double playerY) {
        switch (o.height.get()) {
            case Surface:
                int best = Integer.MIN_VALUE;
                for (Flag f : cl.members) if (f.surfaceY > best) best = f.surfaceY;
                return best != Integer.MIN_VALUE ? best + o.surfaceOffset.get() : o.fixedY.get();
            case Player:
                return Math.floor(playerY);
            default:
                return o.fixedY.get();
        }
    }

    public static void render(Render3DEvent event, Collection<Flag> flags, MarkerOptions o, Colors c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || flags.isEmpty()) return;
        frame++;

        Renderer3D r = event.renderer;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int pcx = ((int) Math.floor(px)) >> 4;
        int pcz = ((int) Math.floor(pz)) >> 4;
        int range = o.renderDistance.get();
        MarkerOptions.Style style = o.style.get();
        boolean markers = o.show.get();
        double thick = o.thickness.get();
        ShapeMode mode = o.shapeMode.get();

        VISIBLE.clear();
        for (Flag f : flags) {
            if (Math.abs(f.chunkX - pcx) > range || Math.abs(f.chunkZ - pcz) > range) continue;
            VISIBLE.add(f);
        }
        if (VISIBLE.isEmpty()) return;

        for (Flag f : VISIBLE) {
            if (f.surfaceY == Integer.MIN_VALUE || (frame + f.chunkX + f.chunkZ) % 120 == 0) {
                int s = Surface.topY(mc.world, f.chunkX, f.chunkZ);
                if (s != Integer.MIN_VALUE) f.surfaceY = s;
            }
            if (o.detectionBoxes.get()) {
                colours(o, c, f.severity, SIDE, LINE);
                r.box(f.ax, f.ay, f.az, f.ax + 1, f.ay + 1, f.az + 1, SIDE, LINE, mode, 0);
            }
        }

        // Touching flagged chunks merge into one shape, the way nearby markers should read as a
        // single area rather than a grid of separate squares.
        List<MarkerCluster> clusters = MarkerCluster.group(VISIBLE);

        if (markers) {
            for (MarkerCluster cl : clusters) {
                colours(o, c, cl.severity, SIDE, LINE);
                double y = markerY(cl, o, py);
                double x1 = cl.blockX1(), z1 = cl.blockZ1(), x2 = cl.blockX2(), z2 = cl.blockZ2();

                if (style == MarkerOptions.Style.Plate || style == MarkerOptions.Style.Both) {
                    r.box(x1, y, z1, x2, y + thick, z2, SIDE, LINE, mode, 0);
                }
                if (style == MarkerOptions.Style.Outline) {
                    r.box(x1, y, z1, x2, y + thick, z2, SIDE, LINE, ShapeMode.Lines, 0);
                }
                if (style == MarkerOptions.Style.Pillar || style == MarkerOptions.Style.Both) {
                    double w = o.pillarWidth.get();
                    double cx = cl.centerX(), cz = cl.centerZ();
                    double top = o.pillarTop.get();
                    if (top > y) r.box(cx - w / 2, y, cz - w / 2, cx + w / 2, top, cz + w / 2, SIDE, LINE, mode, 0);
                }
            }
        }

        if (o.tracers.get() && !clusters.isEmpty()) {
            boolean toMarker = o.tracerTarget.get() == MarkerOptions.TracerTo.Marker;
            TRACE.clear();
            TRACE.addAll(clusters);
            double[] scratch = new double[TRACE.size()];
            for (int i = 0; i < TRACE.size(); i++) {
                MarkerCluster cl = TRACE.get(i);
                Flag top = cl.members.get(0); // strongest member; used for the "aim at the detection" mode
                double tx = toMarker ? cl.centerX() : top.ax + 0.5;
                double tz = toMarker ? cl.centerZ() : top.az + 0.5;
                scratch[i] = (tx - px) * (tx - px) + (tz - pz) * (tz - pz);
            }
            int n = o.tracerCount.get();
            if (TRACE.size() > n) {
                Integer[] order = new Integer[TRACE.size()];
                for (int i = 0; i < order.length; i++) order[i] = i;
                java.util.Arrays.sort(order, Comparator.comparingDouble(i -> scratch[i]));
                List<MarkerCluster> sorted = new ArrayList<>(TRACE.size());
                for (int i : order) sorted.add(TRACE.get(i));
                TRACE.clear();
                TRACE.addAll(sorted);
            }
            int count = Math.min(n, TRACE.size());
            SettingColor tc = c.tracer.get();
            for (int i = 0; i < count; i++) {
                MarkerCluster cl = TRACE.get(i);
                Flag topFlag = cl.members.get(0);
                double tx, ty, tz;
                if (toMarker) {
                    tx = cl.centerX();
                    ty = markerY(cl, o, py) + thick / 2;
                    tz = cl.centerZ();
                } else {
                    tx = topFlag.ax + 0.5;
                    ty = topFlag.ay + 0.5;
                    tz = topFlag.az + 0.5;
                }
                if (o.tracerSeverity.get()) {
                    colours(o, c, cl.severity, TRACER, LINE);
                    TRACER.set(TRACER.r, TRACER.g, TRACER.b, tc.a);
                } else {
                    TRACER.set(tc.r, tc.g, tc.b, tc.a);
                }
                r.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, tx, ty, tz, TRACER);
            }
        }
    }
}
