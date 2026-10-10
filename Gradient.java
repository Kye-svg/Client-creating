package dev.clicksclient.core;

public final class Gradient {
    private Gradient() {}

    public static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    public static int lerp(int a, int b, float t) {
        t = clamp01(t);
        return Math.round(a + (b - a) * t);
    }
}
