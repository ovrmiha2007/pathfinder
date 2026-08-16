package com.lecternscanner.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Baritone-like path / break / place overlay via Minecraft per-tick gizmos.
 */
@EventBusSubscriber(modid = com.lecternscanner.LecternScannerMod.MODID, value = Dist.CLIENT)
public final class BotOverlay {
    private static final List<Vec3> path = new ArrayList<>();
    private static final Set<BlockPos> breakBlocks = new LinkedHashSet<>();
    private static final Set<BlockPos> placeBlocks = new LinkedHashSet<>();
    private static Vec3 goal;
    private static boolean enabled = true;

    private BotOverlay() {
    }

    public static void setEnabled(boolean enabled) {
        BotOverlay.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setPath(List<BlockPos> nodes, Vec3 goalPos) {
        path.clear();
        if (nodes != null) {
            for (BlockPos p : nodes) {
                path.add(new Vec3(p.getX() + 0.5, p.getY() + 0.1, p.getZ() + 0.5));
            }
        }
        goal = goalPos;
    }

    public static void setPathVec(List<Vec3> nodes, Vec3 goalPos) {
        path.clear();
        if (nodes != null) {
            path.addAll(nodes);
        }
        goal = goalPos;
    }

    public static void clearPath() {
        path.clear();
        goal = null;
    }

    public static void setBreakBlocks(Set<BlockPos> blocks) {
        breakBlocks.clear();
        if (blocks != null) {
            breakBlocks.addAll(blocks);
        }
    }

    public static void addBreak(BlockPos pos) {
        if (pos != null) {
            breakBlocks.add(pos.immutable());
        }
    }

    public static void clearBreak() {
        breakBlocks.clear();
    }

    public static void setPlaceBlocks(Set<BlockPos> blocks) {
        placeBlocks.clear();
        if (blocks != null) {
            placeBlocks.addAll(blocks);
        }
    }

    public static void addPlace(BlockPos pos) {
        if (pos != null) {
            placeBlocks.add(pos.immutable());
        }
    }

    public static void clearPlace() {
        placeBlocks.clear();
    }

    public static void clearAll() {
        clearPath();
        clearBreak();
        clearPlace();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // Client tick already runs inside Minecraft.collectPerTickGizmos()
        render();
    }

    private static void render() {
        // Path line (cyan → blue), Baritone-style
        int pathColor = ARGB.colorFromFloat(1.0F, 0.15F, 0.85F, 1.0F);
        int pathNext = ARGB.colorFromFloat(1.0F, 1.0F, 0.85F, 0.15F);
        for (int i = 0; i < path.size() - 1; i++) {
            Vec3 a = path.get(i);
            Vec3 b = path.get(i + 1);
            Gizmos.line(a, b, i == 0 ? pathNext : pathColor, i == 0 ? 4.0F : 2.5F);
            if (i < 64) {
                Gizmos.cuboid(
                        new AABB(a.x - 0.12, a.y, a.z - 0.12, a.x + 0.12, a.y + 0.08, a.z + 0.12),
                        GizmoStyle.fill(ARGB.colorFromFloat(0.55F, 0.2F, 0.7F, 1.0F)));
            }
        }
        if (!path.isEmpty()) {
            Vec3 last = path.getLast();
            Gizmos.cuboid(
                    new AABB(last.x - 0.15, last.y, last.z - 0.15, last.x + 0.15, last.y + 0.12, last.z + 0.15),
                    GizmoStyle.fill(ARGB.colorFromFloat(0.7F, 0.2F, 1.0F, 0.4F)));
        }

        if (goal != null) {
            BlockPos gp = BlockPos.containing(goal);
            Gizmos.cuboid(gp, 0.02F, GizmoStyle.strokeAndFill(
                    ARGB.colorFromFloat(1.0F, 0.1F, 1.0F, 0.2F),
                    2.5F,
                    ARGB.colorFromFloat(0.35F, 0.15F, 1.0F, 0.2F)));
            Gizmos.line(
                    goal.add(0, 0.1, 0),
                    goal.add(0, 1.6, 0),
                    ARGB.colorFromFloat(1.0F, 0.2F, 1.0F, 0.3F),
                    2.0F);
        }

        // Break = red (like Baritone selection)
        for (BlockPos p : breakBlocks) {
            Gizmos.cuboid(p, 0.002F, GizmoStyle.strokeAndFill(
                    ARGB.colorFromFloat(1.0F, 1.0F, 0.15F, 0.15F),
                    2.0F,
                    ARGB.colorFromFloat(0.45F, 1.0F, 0.1F, 0.1F)));
        }

        // Place = green
        for (BlockPos p : placeBlocks) {
            Gizmos.cuboid(p, 0.002F, GizmoStyle.strokeAndFill(
                    ARGB.colorFromFloat(1.0F, 0.15F, 1.0F, 0.25F),
                    2.0F,
                    ARGB.colorFromFloat(0.4F, 0.1F, 0.9F, 0.2F)));
        }

        BlockPos mining = BotUtil.getMiningPos();
        if (mining != null) {
            Gizmos.cuboid(mining, 0.01F, GizmoStyle.strokeAndFill(
                    ARGB.colorFromFloat(1.0F, 1.0F, 0.55F, 0.05F),
                    3.0F,
                    ARGB.colorFromFloat(0.55F, 1.0F, 0.35F, 0.05F)));
        }
    }
}
