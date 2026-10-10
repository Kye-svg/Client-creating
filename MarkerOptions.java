package dev.clicksclient.mc;

import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;

/** The "Markers" and "Tracers" sections shared by every feature. Colours live in each feature's own section. */
public final class MarkerOptions {
    public enum Style { Plate, Pillar, Both, Outline }

    public enum Height { Surface, Fixed, Player }

    public enum TracerTo { Marker, Detection }

    public final Setting<Boolean> show;
    public final Setting<Style> style;
    public final Setting<Height> height;
    public final Setting<Integer> surfaceOffset;
    public final Setting<Integer> fixedY;
    public final Setting<Double> thickness;
    public final Setting<Double> pillarWidth;
    public final Setting<Integer> pillarTop;
    public final Setting<ShapeMode> shapeMode;
    public final Setting<Boolean> severityColors;
    public final Setting<Boolean> detectionBoxes;
    public final Setting<Integer> renderDistance;
    public final Setting<Boolean> extendFarPlane;
    public final Setting<Integer> farPlaneLimit;

    public final Setting<Boolean> tracers;
    public final Setting<TracerTo> tracerTarget;
    public final Setting<Boolean> tracerSeverity;
    public final Setting<Integer> tracerCount;

    public MarkerOptions(Settings settings) {
        SettingGroup m = settings.createGroup("Markers");

        show = m.add(new BoolSetting.Builder()
            .name("show-markers")
            .description("Draw a marker above the ground over every flagged chunk.")
            .defaultValue(true)
            .build());

        style = m.add(new EnumSetting.Builder<Style>()
            .name("marker-style")
            .description("Plate is a flat square, Pillar is a tall beam, Both draws both, Outline is just the frame of the plate.")
            .defaultValue(Style.Both)
            .build());

        height = m.add(new EnumSetting.Builder<Height>()
            .name("marker-height")
            .description("Surface floats the marker just above the ground over the chunk. Fixed uses the Y below. Player stays level with you.")
            .defaultValue(Height.Surface)
            .build());

        surfaceOffset = m.add(new IntSetting.Builder()
            .name("surface-offset")
            .description("How many blocks above the ground the marker floats.")
            .defaultValue(3)
            .range(0, 64)
            .sliderRange(0, 32)
            .visible(() -> height.get() == Height.Surface)
            .build());

        fixedY = m.add(new IntSetting.Builder()
            .name("fixed-y")
            .description("Marker height for the Fixed mode, and the fallback while the ground height is not known yet.")
            .defaultValue(100)
            .range(-64, 2000)
            .sliderRange(-64, 320)
            .build());

        thickness = m.add(new DoubleSetting.Builder()
            .name("plate-thickness")
            .description("Height of the plate in blocks.")
            .defaultValue(1.0)
            .range(0.1, 16.0)
            .sliderRange(0.1, 8.0)
            .visible(() -> style.get() != Style.Pillar)
            .build());

        pillarWidth = m.add(new DoubleSetting.Builder()
            .name("pillar-width")
            .description("Width of the beam in blocks.")
            .defaultValue(1.0)
            .range(0.1, 16.0)
            .sliderRange(0.1, 8.0)
            .visible(() -> style.get() == Style.Pillar || style.get() == Style.Both)
            .build());

        pillarTop = m.add(new IntSetting.Builder()
            .name("pillar-top")
            .description("Y the beam reaches up to.")
            .defaultValue(320)
            .range(-64, 4000)
            .sliderRange(0, 400)
            .visible(() -> style.get() == Style.Pillar || style.get() == Style.Both)
            .build());

        shapeMode = m.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("Whether markers are drawn as sides, lines, or both.")
            .defaultValue(ShapeMode.Both)
            .build());

        severityColors = m.add(new BoolSetting.Builder()
            .name("severity-colors")
            .description("Blend each feature's low colour into its high colour depending on how strong the detection is. Transparency still comes from the fill and outline colours.")
            .defaultValue(true)
            .build());

        detectionBoxes = m.add(new BoolSetting.Builder()
            .name("detection-boxes")
            .description("Also box the exact spot that triggered each flag. Drawn through walls.")
            .defaultValue(true)
            .build());

        renderDistance = m.add(new IntSetting.Builder()
            .name("render-distance")
            .description("Only draw flagged chunks within this many chunks of you.")
            .defaultValue(128)
            .range(2, 1000)
            .sliderRange(8, 512)
            .build());

        extendFarPlane = m.add(new BoolSetting.Builder()
            .name("extend-far-plane")
            .description("Push the camera far plane out so distant markers are not clipped.")
            .defaultValue(true)
            .build());

        farPlaneLimit = m.add(new IntSetting.Builder()
            .name("far-plane-limit")
            .description("Largest far plane distance in blocks. Very large values can cause depth glitches on nearby terrain.")
            .defaultValue(4096)
            .range(512, 30000)
            .sliderRange(512, 16384)
            .visible(() -> extendFarPlane.get())
            .build());

        SettingGroup t = settings.createGroup("Tracers");

        tracers = t.add(new BoolSetting.Builder()
            .name("tracers")
            .description("Draw lines from the centre of your screen to flagged chunks.")
            .defaultValue(true)
            .build());

        tracerTarget = t.add(new EnumSetting.Builder<TracerTo>()
            .name("tracer-target")
            .description("Marker aims at the marker above the chunk. Detection aims at the exact spot that triggered the flag.")
            .defaultValue(TracerTo.Marker)
            .visible(() -> tracers.get())
            .build());

        tracerSeverity = t.add(new BoolSetting.Builder()
            .name("tracer-severity-colors")
            .description("Colour tracers by detection strength instead of each feature's tracer colour.")
            .defaultValue(false)
            .visible(() -> tracers.get())
            .build());

        tracerCount = t.add(new IntSetting.Builder()
            .name("tracer-count")
            .description("Most tracers drawn at once for each feature. The nearest flagged chunks win.")
            .defaultValue(12)
            .range(1, 500)
            .sliderRange(1, 100)
            .visible(() -> tracers.get())
            .build());
    }
}
