package com.lecternscanner.client;

import net.minecraft.util.Mth;

/**
 * Yaw/pitch pair — adapted from Baritone's {@code baritone.api.utils.Rotation} (LGPL).
 */
public final class BotRotation {
    private final float yaw;
    private final float pitch;

    public BotRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public BotRotation add(BotRotation other) {
        return new BotRotation(yaw + other.yaw, pitch + other.pitch);
    }

    public BotRotation subtract(BotRotation other) {
        return new BotRotation(yaw - other.yaw, pitch - other.pitch);
    }

    public BotRotation normalize() {
        return new BotRotation(normalizeYaw(yaw), pitch);
    }

    public BotRotation withPitch(float pitch) {
        return new BotRotation(yaw, pitch);
    }

    public boolean yawIsReallyClose(BotRotation other) {
        float yawDiff = Math.abs(normalizeYaw(yaw) - normalizeYaw(other.yaw));
        return yawDiff < 0.01f || yawDiff > 359.99f;
    }

    public static float normalizeYaw(float yaw) {
        float newYaw = yaw % 360F;
        if (newYaw < -180F) {
            newYaw += 360F;
        }
        if (newYaw > 180F) {
            newYaw -= 360F;
        }
        return newYaw;
    }

    public static float clampPitch(float pitch) {
        return Mth.clamp(pitch, -90.0F, 90.0F);
    }
}
