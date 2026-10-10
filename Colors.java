package dev.clicksclient.mc;

import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/** The five colour settings one feature owns, so each kind of detection is easy to tell apart. */
public final class Colors {
    public final Setting<SettingColor> side;
    public final Setting<SettingColor> line;
    public final Setting<SettingColor> low;
    public final Setting<SettingColor> high;
    public final Setting<SettingColor> tracer;

    public Colors(SettingGroup g, String key, IVisible severityVisible,
                  SettingColor sideDefault, SettingColor lineDefault, SettingColor lowDefault, SettingColor highDefault, SettingColor tracerDefault) {
        side = g.add(new ColorSetting.Builder()
            .name(key + "fill-color")
            .description("Fill colour of this feature's markers.")
            .defaultValue(sideDefault)
            .build());
        line = g.add(new ColorSetting.Builder()
            .name(key + "outline-color")
            .description("Outline colour of this feature's markers.")
            .defaultValue(lineDefault)
            .build());
        low = g.add(new ColorSetting.Builder()
            .name(key + "low-color")
            .description("Colour of weak detections. Only used while severity colours are on in the Markers section.")
            .defaultValue(lowDefault)
            .visible(severityVisible)
            .build());
        high = g.add(new ColorSetting.Builder()
            .name(key + "high-color")
            .description("Colour of strong detections. Only used while severity colours are on in the Markers section.")
            .defaultValue(highDefault)
            .visible(severityVisible)
            .build());
        tracer = g.add(new ColorSetting.Builder()
            .name(key + "tracer-color")
            .description("Colour of this feature's tracer lines.")
            .defaultValue(tracerDefault)
            .build());
    }
}
