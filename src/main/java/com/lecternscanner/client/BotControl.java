package com.lecternscanner.client;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Shared input/look/mining helpers — fixes menu freeze, mouse fight, hold-to-break.
 */
public final class BotControl {
    private static Runnable pendingAction;
    private static int pendingDelay;
    private static Float lookYaw;
    private static Float lookPitch;
    private static Field missTimeField;
    private static Field mouseAccumX;
    private static Field mouseAccumY;

    private BotControl() {
    }

    public static void suppressMouseLook(Minecraft mc) {
        try {
            if (mouseAccumX == null) {
                mouseAccumX = mc.mouseHandler.getClass().getDeclaredField("accumulatedDX");
                mouseAccumY = mc.mouseHandler.getClass().getDeclaredField("accumulatedDY");
                mouseAccumX.setAccessible(true);
                mouseAccumY.setAccessible(true);
            }
            mouseAccumX.setDouble(mc.mouseHandler, 0.0);
            mouseAccumY.setDouble(mc.mouseHandler, 0.0);
            mc.mouseHandler.setIgnoreFirstMove();
        } catch (ReflectiveOperationException ignored) {
        }
        applyLookLock(mc.player);
    }

    public static void queueAfterMenu(Runnable action) {
        pendingAction = action;
        pendingDelay = 3; // let screen fully close + missTime/grab settle
    }

    public static void tickPending() {
        if (pendingAction == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return;
        }
        if (pendingDelay > 0) {
            pendingDelay--;
            clearMissTime(mc);
            return;
        }
        Runnable run = pendingAction;
        pendingAction = null;
        clearMissTime(mc);
        forceCleanInput(mc.player);
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.setIgnoreFirstMove();
        } else {
            mc.mouseHandler.grabMouse();
            clearMissTime(mc);
        }
        run.run();
    }

    public static void forceCleanInput(LocalPlayer player) {
        MovementKeys.clear();
        MovementKeys.ensureKeyboardInput();
        if (player == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        clearMissTime(mc);
        clearLookLock();
    }

    public static void clearMissTime(Minecraft mc) {
        try {
            if (missTimeField == null) {
                missTimeField = Minecraft.class.getDeclaredField("missTime");
                missTimeField.setAccessible(true);
            }
            missTimeField.setInt(mc, 0);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public static void holdAttackKey(Minecraft mc, boolean hold) {
        MovementKeys.setAttack(hold);
    }

    public static void releaseAttackKey(Minecraft mc) {
        MovementKeys.setAttack(false);
    }

    /** Desired look applied in Pre (packets) and again after mouse (visual). */
    public static void setLookLock(float yaw, float pitch) {
        lookYaw = yaw;
        lookPitch = Mth.clamp(pitch, -90.0F, 90.0F);
    }

    public static void clearLookLock() {
        lookYaw = null;
        lookPitch = null;
    }

    /** True while Lectern Scanner UI (settings or logic editor) is open. */
    public static boolean isSettingsMenuOpen(Minecraft mc) {
        return mc != null && (mc.screen instanceof LecternScannerMenuScreen
                || mc.screen instanceof com.lecternscanner.client.logic.LogicEditorScreen);
    }

    /**
     * Close bot-owned GUIs (inventory/crafting). Never closes the settings menu.
     */
    public static void closeBotScreens(Minecraft mc) {
        if (mc == null || mc.screen == null) {
            return;
        }
        if (mc.screen instanceof LecternScannerMenuScreen
                || mc.screen instanceof com.lecternscanner.client.logic.LogicEditorScreen) {
            return;
        }
        mc.setScreen(null);
    }

    public static void applyLookLock(LocalPlayer player) {
        if (player == null || lookYaw == null || lookPitch == null) {
            return;
        }
        player.setYRot(lookYaw);
        player.setXRot(lookPitch);
        player.yRotO = lookYaw;
        player.xRotO = lookPitch;
        player.yHeadRot = lookYaw;
        player.yHeadRotO = lookYaw;
        player.yBodyRot = lookYaw;
        player.yBodyRotO = lookYaw;
    }

    public static void applyRotation(LocalPlayer player, BotRotation rot, boolean lock) {
        float yaw = BotRotation.normalizeYaw(rot.getYaw());
        float pitch = BotRotation.clampPitch(rot.getPitch());
        if (lock) {
            setLookLock(yaw, pitch);
            applyLookLock(player);
        } else {
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.yHeadRot = yaw;
            player.yBodyRot = yaw;
        }
    }

    /**
     * Full look at a point (Baritone RotationUtils) — mining / combat / place.
     */
    public static void lookAt(LocalPlayer player, Vec3 target, boolean lock) {
        applyRotation(player, BotRotationUtils.lookAt(player, target), lock);
    }

    public static void lookAtBlock(LocalPlayer player, BlockPos pos, boolean lock) {
        applyRotation(player, BotRotationUtils.lookAtBlock(player, pos), lock);
    }

    /**
     * Baritone {@code MovementHelper.moveTowards}: yaw toward next path block, keep pitch.
     */
    public static void moveTowards(LocalPlayer player, BlockPos dest) {
        applyRotation(player, BotRotationUtils.moveTowards(player, dest), true);
    }

    /**
     * If {@code desired} is behind another block, return the first solid block on the ray.
     * Also returns a recommended face to mine.
     */
    public static BlockHitResult traceMineTarget(Minecraft mc, LocalPlayer player, BlockPos desired) {
        if (mc.level == null) {
            return null;
        }
        Vec3 eyes = player.getEyePosition();
        Vec3 dest = Vec3.atCenterOf(desired);
        // Slightly past the center so we hit the block
        Vec3 end = eyes.add(dest.subtract(eyes).normalize().scale(eyes.distanceTo(dest) + 1.0));
        BlockHitResult hit = mc.level.clip(new ClipContext(
                eyes, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.BLOCK) {
            return hit;
        }
        Direction face = Direction.getApproximateNearest(
                eyes.x - dest.x, eyes.y - dest.y, eyes.z - dest.z);
        return new BlockHitResult(dest, face, desired, false);
    }

    public static BlockPos firstBreakableOnRay(Minecraft mc, LocalPlayer player, BlockPos desired) {
        BlockHitResult hit = traceMineTarget(mc, player, desired);
        if (hit == null) {
            return desired;
        }
        BlockPos p = hit.getBlockPos();
        if (mc.level != null && !mc.level.getBlockState(p).isAir()) {
            return p;
        }
        return desired;
    }
}
