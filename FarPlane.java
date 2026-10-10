package dev.clicksclient.mc;

import java.util.HashMap;
import java.util.Map;

/**
 * Modules report how far away their farthest marker is; the GameRenderer mixin then pushes the camera
 * far plane out to match so distant markers are not clipped by the vanilla far plane.
 */
public final class FarPlane {
    private FarPlane() {}

    private static final Map<Object, Float> reports = new HashMap<>();
    private static volatile float target = 0f;

    public static void report(Object who, float distance) {
        if (distance <= 0f) reports.remove(who);
        else reports.put(who, distance);
        float max = 0f;
        for (float f : reports.values()) if (f > max) max = f;
        target = max;
    }

    /** Called from the mixin with the vanilla value; never returns less than that. */
    public static float extend(float current) {
        float t = target;
        return t > current ? t : current;
    }
}
