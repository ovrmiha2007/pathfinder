package com.lecternscanner.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Local A* for survival walking on loaded client chunks.
 * Player hitbox is treated as {@link #PLAYER_HEIGHT} blocks tall (feet + head).
 */
public final class SurvivalAStar {
    /** Standing player occupies feet and feet+1 (vanilla ~1.8m). */
    public static final int PLAYER_HEIGHT = 2;

    private static final int MAX_NODES = 18000;
    /** Safe drop without fall damage (vanilla: damage ≈ fallDistance − 3). */
    private static final int MAX_SAFE_DROP = 3;
    private static final int MAX_DROP = 6;

    private SurvivalAStar() {
    }

    public static List<BlockPos> findPath(ClientLevel level, BlockPos startFeet, BlockPos goalFeet, int maxHorizRange) {
        BlockPos start = findStandPos(level, startFeet);
        BlockPos goal = findStandPosNear(level, goalFeet, 12);
        if (start == null || goal == null) {
            return List.of();
        }

        if (start.distManhattan(goal) > maxHorizRange) {
            goal = projectToward(start, goal, maxHorizRange);
            goal = findStandPosNear(level, goal, 10);
            if (goal == null) {
                return List.of();
            }
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Double> bestG = new HashMap<>();
        Map<Long, Long> cameFrom = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        long startKey = start.asLong();
        open.add(new Node(start, 0, heuristic(start, goal), 0));
        bestG.put(startKey, 0.0);

        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node cur = open.poll();
            long ck = cur.pos.asLong();
            if (closed.contains(ck)) {
                continue;
            }
            closed.add(ck);
            expanded++;

            if (cur.pos.getX() == goal.getX() && cur.pos.getZ() == goal.getZ()
                    && Math.abs(cur.pos.getY() - goal.getY()) <= 1) {
                return reconstruct(cameFrom, cur.pos, start);
            }

            for (Move move : neighbors(level, cur.pos)) {
                long nk = move.to.asLong();
                if (closed.contains(nk)) {
                    continue;
                }
                if (horizontalDist(start, move.to) > maxHorizRange + 4) {
                    continue;
                }
                double ng = cur.g + move.cost;
                Double prev = bestG.get(nk);
                if (prev != null && ng >= prev) {
                    continue;
                }
                bestG.put(nk, ng);
                cameFrom.put(nk, ck);
                double f = ng + heuristic(move.to, goal);
                open.add(new Node(move.to, ng, f, cur.depth + 1));
            }
        }
        return List.of();
    }

    private static List<Move> neighbors(ClientLevel level, BlockPos from) {
        List<Move> out = new ArrayList<>(16);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos flat = from.relative(dir);

            // Flat walk — clear 2-tall (Baritone Traverse with no mining)
            if (canTraverseFlat(level, from, flat)) {
                out.add(new Move(flat, costFor(level, flat)));
            } else if (PathMoves.isDiggableTraverse(level, from, flat)) {
                // Baritone Traverse with mining cost on dest + dest.above()
                double dig = digCostEstimate(level, flat) + digCostEstimate(level, flat.above());
                if (dig < 500_000) {
                    out.add(new Move(flat, costFor(level, flat) + dig + 2.0));
                }
            }

            // Step up 1 — clear landing (Baritone Ascend)
            BlockPos up = flat.above();
            if (canTraverseStepUp(level, from, flat, up)) {
                out.add(new Move(up, costFor(level, up) + 1.2));
            } else if (hasFloor(level, flat) && !SurvivalAStar.isAirLike(level, flat)
                    && PathMoves.hasWalkableFloor(level, up)) {
                // Dig for ascend: dest body/head + src ceiling
                double dig = digCostEstimate(level, up)
                        + digCostEstimate(level, up.above())
                        + digCostEstimate(level, from.above(2));
                if (dig < 500_000 && isAirLike(level, from.above()) /* standing */) {
                    out.add(new Move(up, costFor(level, up) + dig + 3.0));
                }
            }

            // Drop down
            for (int drop = 1; drop <= MAX_DROP; drop++) {
                BlockPos down = flat.below(drop);
                if (canStandAt(level, down) && clearFallColumn(level, flat, drop)) {
                    double dropCost = drop <= MAX_SAFE_DROP
                            ? drop * 0.5
                            : 40.0 + (drop - MAX_SAFE_DROP) * 25.0;
                    out.add(new Move(down, costFor(level, down) + dropCost));
                    break;
                }
            }
        }
        return out;
    }

    private static boolean hasFloor(ClientLevel level, BlockPos feetOrStep) {
        return !level.getBlockState(feetOrStep).getCollisionShape(level, feetOrStep).isEmpty()
                || PathMoves.hasWalkableFloor(level, feetOrStep.above());
    }

    /** Soft dig cost proxy (hardness * 20); unbreakable → huge. */
    private static double digCostEstimate(ClientLevel level, BlockPos pos) {
        if (isAirLike(level, pos)) {
            return 0;
        }
        float h = level.getBlockState(pos).getDestroySpeed(level, pos);
        if (h < 0) {
            return 1_000_000;
        }
        return Math.max(1.0, h * 20.0);
    }

    /** Body (feet) + head (feet+1) both passable. */
    public static boolean hasBodyClearance(ClientLevel level, BlockPos feet) {
        for (int dy = 0; dy < PLAYER_HEIGHT; dy++) {
            if (!isAirLike(level, feet.above(dy))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Can stand with feet at {@code feet}: solid floor + 2-tall air column.
     */
    public static boolean canStandAt(ClientLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState floor = level.getBlockState(below);
        VoxelShape shape = floor.getCollisionShape(level, below);
        if (shape.isEmpty() && !floor.getFluidState().is(FluidTags.WATER)) {
            return false;
        }
        if (level.getFluidState(feet).is(FluidTags.LAVA) || level.getFluidState(below).is(FluidTags.LAVA)) {
            return false;
        }
        return hasBodyClearance(level, feet);
    }

    /** Same Y walk into adjacent cell — both cells need 2-tall clearance. */
    public static boolean canTraverseFlat(ClientLevel level, BlockPos from, BlockPos to) {
        if (to.getY() != from.getY()) {
            return false;
        }
        if (!canStandAt(level, to)) {
            return false;
        }
        // Already standing in from — still require head clear (from+1) which canStandAt(from) implies
        return hasBodyClearance(level, from);
    }

    /**
     * Step onto the block in front: landing at {@code up} (= flat.above()).
     * Requires solid step at {@code flat}, 2-tall landing, and jump headroom at from+2.
     */
    public static boolean canTraverseStepUp(ClientLevel level, BlockPos from, BlockPos flat, BlockPos up) {
        if (up.getY() != from.getY() + 1) {
            return false;
        }
        // Must actually be a step (something to land on)
        BlockState step = level.getBlockState(flat);
        if (step.getCollisionShape(level, flat).isEmpty()) {
            return false;
        }
        if (!canStandAt(level, up)) {
            return false;
        }
        // Jump: head goes through from+2
        if (!isAirLike(level, from.above(PLAYER_HEIGHT))) {
            return false;
        }
        return true;
    }

    /** True if player can legally walk/jump from → to (same rules as A*). */
    public static boolean canTraverse(ClientLevel level, BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        if (dy == 0) {
            return canTraverseFlat(level, from, to);
        }
        if (dy == 1) {
            BlockPos flat = new BlockPos(to.getX(), from.getY(), to.getZ());
            return canTraverseStepUp(level, from, flat, to);
        }
        if (dy < 0 && dy >= -MAX_DROP) {
            BlockPos flat = new BlockPos(to.getX(), from.getY(), to.getZ());
            return canStandAt(level, to) && clearFallColumn(level, flat, -dy);
        }
        return false;
    }

    /**
     * Front cell needs mining for a 2-tall player to pass at same Y.
     * Returns body and/or head positions that block; empty if clear or step-up.
     */
    public static List<BlockPos> blockingForTwoTall(ClientLevel level, BlockPos feet, Direction dir) {
        List<BlockPos> out = new ArrayList<>(2);
        BlockPos body = feet.relative(dir);
        BlockPos head = body.above();
        boolean solidBody = !isAirLike(level, body);
        boolean solidHead = !isAirLike(level, head);
        // Step-up case: body solid, head clear, and can land on top — not "blocking walk"
        if (solidBody && !solidHead && canStandAt(level, body.above())
                && isAirLike(level, feet.above(PLAYER_HEIGHT))) {
            return out;
        }
        if (solidBody) {
            out.add(body);
        }
        if (solidHead) {
            out.add(head);
        }
        return out;
    }

    private static boolean clearFallColumn(ClientLevel level, BlockPos topFeetCandidate, int drop) {
        // Each layer of the fall must fit 2-tall body
        for (int i = 0; i < drop; i++) {
            BlockPos feet = topFeetCandidate.below(i);
            if (!hasBodyClearance(level, feet)) {
                return false;
            }
        }
        return true;
    }

    private static double costFor(ClientLevel level, BlockPos feet) {
        BlockState below = level.getBlockState(feet.below());
        if (below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CAMPFIRE) || below.is(Blocks.SOUL_CAMPFIRE)) {
            return 8.0;
        }
        FluidState fluid = level.getFluidState(feet);
        if (fluid.is(FluidTags.WATER)) {
            return 3.0;
        }
        if (fluid.is(FluidTags.LAVA) || level.getFluidState(feet.below()).is(FluidTags.LAVA)) {
            return 1000.0;
        }
        double cost = 1.0;
        if (isOneBlockWideCorridor(level, feet)) {
            cost += 12.0;
        } else if (clearanceScore(level, feet) <= 1) {
            cost += 4.0;
        }
        return cost;
    }

    public static boolean isOneBlockWideCorridor(ClientLevel level, BlockPos feet) {
        int blockedSides = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (!hasBodyClearance(level, side)) {
                blockedSides++;
            }
        }
        return blockedSides >= 3;
    }

    private static int clearanceScore(ClientLevel level, BlockPos feet) {
        int open = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (hasBodyClearance(level, feet.relative(dir))) {
                open++;
            }
        }
        return open;
    }

    public static boolean isAirLike(ClientLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        if (!st.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        return !st.getFluidState().is(FluidTags.LAVA);
    }

    public static BlockPos findStandPos(ClientLevel level, BlockPos approx) {
        for (int dy = 0; dy <= 4; dy++) {
            BlockPos p = approx.below(dy);
            if (canStandAt(level, p)) {
                return p;
            }
            p = approx.above(dy);
            if (canStandAt(level, p)) {
                return p;
            }
        }
        return canStandAt(level, approx) ? approx : null;
    }

    public static BlockPos findStandPosNear(ClientLevel level, BlockPos center, int radius) {
        BlockPos best = findStandPos(level, center);
        if (best != null) {
            return best;
        }
        BlockPos found = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 6; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (canStandAt(level, p)) {
                        int d = Math.abs(dx) + Math.abs(dz) + Math.abs(dy);
                        if (d < bestDist) {
                            bestDist = d;
                            found = p;
                        }
                    }
                }
            }
        }
        return found;
    }

    private static BlockPos projectToward(BlockPos from, BlockPos to, int maxManhattan) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dist = Math.abs(dx) + Math.abs(dz);
        if (dist <= maxManhattan) {
            return to;
        }
        double scale = (double) maxManhattan / dist;
        return new BlockPos(
                from.getX() + (int) Math.round(dx * scale),
                to.getY(),
                from.getZ() + (int) Math.round(dz * scale)
        );
    }

    private static int horizontalDist(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) + Math.abs(a.getY() - b.getY()) * 1.1;
    }

    private static List<BlockPos> reconstruct(Map<Long, Long> cameFrom, BlockPos end, BlockPos start) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos cur = end;
        path.add(cur);
        while (!cur.equals(start)) {
            Long prev = cameFrom.get(cur.asLong());
            if (prev == null) {
                break;
            }
            cur = BlockPos.of(prev);
            path.add(cur);
        }
        Collections.reverse(path);
        return path;
    }

    private record Node(BlockPos pos, double g, double f, int depth) {
    }

    private record Move(BlockPos to, double cost) {
    }

    public static Vec3 toVec(BlockPos feet) {
        return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
    }
}
