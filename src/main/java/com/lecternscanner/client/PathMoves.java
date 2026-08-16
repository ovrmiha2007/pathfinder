package com.lecternscanner.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Baritone-style movement prep: each step has an explicit {@code positionsToBreak} list.
 * Adapted from Baritone {@code MovementTraverse} / {@code MovementAscend} / {@code Movement.prepared} (LGPL).
 * <ul>
 *   <li>Traverse (flat): break {@code dest.above()} then {@code dest} (head then feet)</li>
 *   <li>Ascend (+1): break {@code dest}, {@code src.above(2)}, {@code dest.above()}</li>
 *   <li>Descend: break column while falling into dest</li>
 * </ul>
 * Dig those first; only then {@code moveTowards(dest)}.
 */
public final class PathMoves {
    private PathMoves() {
    }

    public enum Kind {
        TRAVERSE,
        ASCEND,
        DESCEND,
        UNKNOWN
    }

    public record Step(Kind kind, BlockPos src, BlockPos dest, List<BlockPos> toBreak) {
    }

    /**
     * Build the next atomic cardinal step from {@code feet} toward {@code next}
     * (Baritone never diagonal-walks a single movement).
     */
    public static Step stepToward(BlockPos feet, BlockPos next) {
        BlockPos cardinal = BotUtil.cardinalStepToward(feet, next);
        int dy = cardinal.getY() - feet.getY();
        // If next is only Y change via path node with same XZ after align — handle rise on dest
        BlockPos dest = cardinal;
        if (dest.equals(feet) && !next.equals(feet)) {
            // same column — ascend/descend in place
            dest = feet.above(Integer.signum(next.getY() - feet.getY()));
            dy = dest.getY() - feet.getY();
        }
        if (dy == 0) {
            // flat traverse into dest (feet at dest)
            return new Step(Kind.TRAVERSE, feet, dest, traverseBreaks(dest));
        }
        if (dy == 1) {
            // Ascend onto dest: dest is the landing feet pos
            return new Step(Kind.ASCEND, feet, dest, ascendBreaks(feet, dest));
        }
        if (dy < 0) {
            return new Step(Kind.DESCEND, feet, dest, descendBreaks(feet, dest));
        }
        return new Step(Kind.UNKNOWN, feet, dest, List.of());
    }

    /** Baritone MovementTraverse: {@code new BetterBlockPos[]{to.above(), to}} */
    public static List<BlockPos> traverseBreaks(BlockPos destFeet) {
        List<BlockPos> list = new ArrayList<>(2);
        list.add(destFeet.above());
        list.add(destFeet);
        return list;
    }

    /**
     * Baritone MovementAscend: {@code new BetterBlockPos[]{dest, src.above(2), dest.above()}}
     * dest = landing feet; the step block is dest.below().
     */
    public static List<BlockPos> ascendBreaks(BlockPos srcFeet, BlockPos destFeet) {
        List<BlockPos> list = new ArrayList<>(3);
        list.add(destFeet);           // body at landing
        list.add(srcFeet.above(2));   // ceiling while jumping
        list.add(destFeet.above());   // head at landing
        return list;
    }

    public static List<BlockPos> descendBreaks(BlockPos srcFeet, BlockPos destFeet) {
        List<BlockPos> list = new ArrayList<>(4);
        // Clear the column we're dropping into (2-tall at dest)
        list.add(destFeet.above());
        list.add(destFeet);
        // And space leaving src horizontally if dest is offset
        if (srcFeet.getX() != destFeet.getX() || srcFeet.getZ() != destFeet.getZ()) {
            BlockPos edge = new BlockPos(destFeet.getX(), srcFeet.getY(), destFeet.getZ());
            list.add(edge.above());
            list.add(edge);
        }
        return list;
    }

    /** Still-solid blocks from toBreak that block a 2-tall player. */
    public static List<BlockPos> remainingBreaks(ClientLevel level, List<BlockPos> toBreak) {
        List<BlockPos> out = new ArrayList<>(toBreak.size());
        for (BlockPos p : toBreak) {
            if (!SurvivalAStar.isAirLike(level, p)) {
                out.add(p);
            }
        }
        return out;
    }

    /** True if floor under destFeet is solid enough to stand (Baritone canWalkOn-ish). */
    public static boolean hasWalkableFloor(ClientLevel level, BlockPos destFeet) {
        BlockPos below = destFeet.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                || level.getFluidState(destFeet).is(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * Horizontal neighbor that needs digging to become a standable traverse target
     * (floor exists, but body and/or head blocked).
     */
    public static boolean isDiggableTraverse(ClientLevel level, BlockPos from, BlockPos destFeet) {
        if (destFeet.getY() != from.getY()) {
            return false;
        }
        if (!hasWalkableFloor(level, destFeet)) {
            return false;
        }
        if (SurvivalAStar.canStandAt(level, destFeet)) {
            return false; // already clear — normal walk
        }
        // Must not be lava
        return !level.getFluidState(destFeet).is(net.minecraft.tags.FluidTags.LAVA)
                && !level.getFluidState(destFeet.below()).is(net.minecraft.tags.FluidTags.LAVA);
    }

    public static Direction horizontalDir(BlockPos from, BlockPos to) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        if (dx == 0 && dz == 0) {
            return Direction.NORTH;
        }
        return Direction.getApproximateNearest(dx, 0, dz);
    }
}
