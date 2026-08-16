package com.lecternscanner.client;

/**
 * Cheat vision / pathing mode — enabled by the CHEAT logic node.
 * Uses all chunks currently in client memory; ignores LOS / "exposed face" limits for targeting.
 */
public final class BotCheat {
    private static boolean enabled;
    /** Max Manhattan-ish scan radius when no work-area AABB is set. */
    private static int scanRadius = 96;

    private BotCheat() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static int scanRadius() {
        return Math.max(16, Math.min(256, scanRadius));
    }

    public static void setScanRadius(int r) {
        scanRadius = Math.max(16, Math.min(256, r));
    }

    public static void reset() {
        enabled = false;
        scanRadius = 96;
    }
}
