package dev.clicksclient.mc;

import net.minecraft.block.BlockState;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;

/** Decides whether a block state change is a redstone-style on/off flip. */
public final class RedstoneStates {
    private RedstoneStates() {}

    public static boolean flipped(BlockState a, BlockState b) {
        return diff(a, b, Properties.LIT)
            || diff(a, b, Properties.POWERED)
            || diff(a, b, Properties.EXTENDED)
            || diff(a, b, Properties.TRIGGERED)
            || diff(a, b, Properties.ENABLED)
            || zeroCross(a, b);
    }

    private static boolean diff(BlockState a, BlockState b, BooleanProperty p) {
        if (!a.contains(p) || !b.contains(p)) return false;
        boolean x = a.get(p);
        boolean y = b.get(p);
        return x != y;
    }

    /** Redstone wire and similar: only count the signal appearing or disappearing, not every strength change. */
    private static boolean zeroCross(BlockState a, BlockState b) {
        if (!a.contains(Properties.POWER) || !b.contains(Properties.POWER)) return false;
        int x = a.get(Properties.POWER);
        int y = b.get(Properties.POWER);
        return (x == 0) != (y == 0);
    }
}
