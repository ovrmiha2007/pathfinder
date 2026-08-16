package com.lecternscanner.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Look/aim math adapted from Baritone {@code RotationUtils} + {@code MovementHelper.moveTowards} (LGPL).
 * <p>
 * Walking: rotate <b>yaw only</b> toward the next block center, keep current pitch.
 * Mining/placing: full yaw+pitch toward the point.
 */
public final class BotRotationUtils {
    public static final double RAD_TO_DEG = 180.0 / Math.PI;

    private BotRotationUtils() {
    }

    public static BotRotation current(LocalPlayer player) {
        return new BotRotation(player.getYRot(), player.getXRot());
    }

    /** Block center — same as Baritone {@code VecUtils.getBlockPosCenter}. */
    public static Vec3 blockCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /**
     * Baritone {@code wrapAnglesToRelative}: avoid spinning the long way around.
     */
    public static BotRotation wrapAnglesToRelative(BotRotation current, BotRotation target) {
        if (current.yawIsReallyClose(target)) {
            return new BotRotation(current.getYaw(), target.getPitch());
        }
        return target.subtract(current).normalize().add(current);
    }

    /**
     * Baritone {@code calcRotationFromVec3d(orig, dest, current)}.
     * Note: uses {@code orig - dest} deltas (eye → target via Baritone's convention).
     */
    public static BotRotation calcRotationFromVec3d(Vec3 orig, Vec3 dest, BotRotation current) {
        return wrapAnglesToRelative(current, calcRotationFromVec3d(orig, dest));
    }

    /** Absolute rotation from eye to dest (Baritone private calcRotationFromVec3d). */
    public static BotRotation calcRotationFromVec3d(Vec3 orig, Vec3 dest) {
        double dx = orig.x - dest.x;
        double dy = orig.y - dest.y;
        double dz = orig.z - dest.z;
        double yaw = Mth.atan2(dx, -dz);
        double dist = Math.sqrt(dx * dx + dz * dz);
        double pitch = Mth.atan2(dy, dist);
        return new BotRotation(
                (float) (yaw * RAD_TO_DEG),
                (float) (pitch * RAD_TO_DEG)
        );
    }

    /**
     * Baritone {@code MovementHelper.moveTowards}: face the next path cell with
     * <b>yaw only</b> (pitch stays as-is) so the bot walks straight instead of staring at the ground.
     */
    public static BotRotation moveTowards(LocalPlayer player, BlockPos dest) {
        BotRotation cur = current(player);
        BotRotation to = calcRotationFromVec3d(player.getEyePosition(), blockCenter(dest), cur);
        return to.withPitch(cur.getPitch());
    }

    /** Full aim at a point (mining / placing / combat). */
    public static BotRotation lookAt(LocalPlayer player, Vec3 dest) {
        return calcRotationFromVec3d(player.getEyePosition(), dest, current(player));
    }

    public static BotRotation lookAtBlock(LocalPlayer player, BlockPos pos) {
        return lookAt(player, blockCenter(pos));
    }
}
