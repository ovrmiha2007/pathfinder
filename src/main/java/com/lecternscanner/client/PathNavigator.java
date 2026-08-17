package com.lecternscanner.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Navigation for creative fly OR survival walking (A* + jump/sprint).
 */
public final class PathNavigator {
    public enum State { IDLE, MOVING, ARRIVED, STUCK }
    public enum Mode { AUTO, FORCE_WALK, FORCE_FLY }

    private State state = State.IDLE;
    private Mode mode = Mode.AUTO;
    private Vec3 target;
    private float flySpeed = 0.35f;
    private int stuckTicks;
    private int analyzeCooldown;
    private int repathCooldown;
    private String lastAnalysis = "";
    private Runnable onArrive;

    private final List<BlockPos> walkPath = new ArrayList<>();
    private int walkIndex;

    private enum WaterMode { NONE, PLACE, SAIL, RECOVER }
    private WaterMode waterMode = WaterMode.NONE;
    private int waterTicks;
    private BlockPos digTarget;
    private int moveGrace; // don't dig/stuck right after start
    private int foodHuntCooldown; // avoid endless cow-chasing off path
    private int scoopTicks; // walk to item drops after mining
    private int pillarTicksLeft;
    private double pillarTargetY;
    private int digCountThisStretch; // abort endless 1-block tunneling
    private int bridgeTicksLeft;
    private boolean repathAfterLanding;
    private double peakFallDistance;
    private int noJumpGrace; // after fall / repath — don't spam jump

    private Float savedFlySpeed;
    private boolean controlling;

    public State getState() {
        return state;
    }

    public boolean isMoving() {
        return state == State.MOVING;
    }

    public String getLastAnalysis() {
        return lastAnalysis;
    }

    public void setFlySpeed(float flySpeed) {
        this.flySpeed = Mth.clamp(flySpeed, 0.05f, 2.0f);
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    public void goTo(Vec3 target, Runnable onArrive) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        stop(false);
        this.target = target;
        this.onArrive = onArrive;
        this.state = State.MOVING;
        this.stuckTicks = 0;
        this.walkPath.clear();
        this.walkIndex = 0;
        this.waterMode = WaterMode.NONE;
        this.digTarget = null;
        this.moveGrace = 40;
        this.foodHuntCooldown = 0;
        this.scoopTicks = 0;
        this.pillarTicksLeft = 0;
        this.digCountThisStretch = 0;
        this.bridgeTicksLeft = 0;
        this.repathAfterLanding = false;
        this.peakFallDistance = 0;
        this.noJumpGrace = 0;
        takeControl(player);
        boolean walk = useWalk(player);
        if (walk) {
            repath(mc.level, player, true);
        }
        analyze(mc, player);
        chat(mc, "§aНавігація (" + (walk ? "виживання/пішки" : "політ") + ") → " + fmt(target));
        chat(mc, "§7Аналіз: " + lastAnalysis);
        if (walk && walkPath.isEmpty()) {
            chat(mc, "§eЛокальний шлях поки порожній — іду напряму, перебудую коли підвантажаться чанки");
        }
    }

    public void stop(boolean announce) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        state = State.IDLE;
        target = null;
        onArrive = null;
        walkPath.clear();
        walkIndex = 0;
        waterMode = WaterMode.NONE;
        digTarget = null;
        waterMode = WaterMode.NONE;
        scoopTicks = 0;
        pillarTicksLeft = 0;
        bridgeTicksLeft = 0;
        repathAfterLanding = false;
        peakFallDistance = 0;
        noJumpGrace = 0;
        BotUtil.stopMining(mc);
        BotOverlay.clearAll();
        releaseControl(player);
        if (announce) {
            chat(mc, "§eНавігація зупинена");
        }
    }

    public void tick() {
        if (state != State.MOVING || target == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            stop(true);
            return;
        }

        if (analyzeCooldown-- <= 0) {
            analyze(mc, player);
            analyzeCooldown = 40;
        }

        double dist = player.position().distanceTo(target);

        // Arrive only on foot (not mid-boat unless very close on land)
        if (dist < 3.5 && waterMode == WaterMode.NONE && !(player.getVehicle() instanceof AbstractBoat)) {
            finishArrive(mc, player);
            return;
        }

        if (useWalk(player)) {
            // combat / hunger interrupt walking (not flying)
            LivingEntity hostile = BotUtil.nearestHostile(level, player, 8);
            if (hostile != null && waterMode == WaterMode.NONE) {
                lastAnalysis = "бій";
                MovementKeys.setMove(player.distanceTo(hostile) > 2.6, false, false, true);
                BotUtil.attackTick(mc, player, hostile);
                hud(mc, player, dist, "FIGHT");
                return;
            }
            if (BotUtil.shouldEat(player) && waterMode == WaterMode.NONE) {
                lastAnalysis = "їжа";
                MovementKeys.setMove(false, false, false, false);
                BotUtil.eatTick(mc, player);
                hud(mc, player, dist, "EAT");
                return;
            }
            // Hunt food along the route when stock is low (prep may have skipped this)
            if (waterMode == WaterMode.NONE && foodHuntCooldown <= 0
                    && BotUtil.countFoodNutrition(player) < 16) {
                LivingEntity prey = BotUtil.nearestFoodAnimal(level, player, 14);
                if (prey != null) {
                    lastAnalysis = "полювання по дорозі";
                    BotUtil.lookAtEntity(player, prey);
                    double pd = player.distanceTo(prey);
                    if (pd > 2.5) {
                        MovementKeys.setMove(true, player.horizontalCollision, false, true);
                    } else {
                        MovementKeys.setMove(false, false, false, false);
                        BotUtil.attackTick(mc, player, prey);
                        if (!prey.isAlive()) {
                            foodHuntCooldown = 60; // brief pause before next hunt
                        }
                    }
                    hud(mc, player, dist, "HUNT");
                    return;
                }
            } else if (foodHuntCooldown > 0) {
                foodHuntCooldown--;
            }
            if (player.isUsingItem() && waterMode == WaterMode.NONE) {
                mc.gameMode.releaseUsingItem(player);
            }
            tickWalk(mc, level, player, dist);
        } else {
            tickFly(level, player, dist);
        }

        hud(mc, player, dist, useWalk(player) ? (waterMode == WaterMode.NONE ? "WALK" : "BOAT") : "FLY");
    }

    private void hud(Minecraft mc, LocalPlayer player, double dist, String tag) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(
                    "§bNav§f[" + tag + "] §f" + String.format("%.0f", dist) + "m §7| " + lastAnalysis), true);
        }
    }

    private void tickWalk(Minecraft mc, ClientLevel level, LocalPlayer player, double distToGoal) {
        if (tickBoat(mc, level, player)) {
            return;
        }

        if (moveGrace > 0) {
            moveGrace--;
        }
        if (noJumpGrace > 0) {
            noJumpGrace--;
        }

        // Falling: never jump-spam; force repath once we land
        if (!player.onGround() && !(player.getVehicle() instanceof AbstractBoat)) {
            peakFallDistance = Math.max(peakFallDistance, player.fallDistance);
            if (player.getDeltaMovement().y < -0.15 || player.fallDistance > 0.8f) {
                repathAfterLanding = true;
                lastAnalysis = "падіння — чекаю землю";
                MovementKeys.setMove(false, false, false, false);
                updateOverlay(player);
                return;
            }
        } else if (repathAfterLanding || peakFallDistance > 1.5f) {
            lastAnalysis = "після падіння — новий шлях";
            chat(mc, "§eВпав — перебудовую маршрут");
            digTarget = null;
            BotUtil.stopMining(mc);
            walkPath.clear();
            walkIndex = 0;
            repath(level, player, true);
            repathCooldown = 20;
            stuckTicks = 0;
            noJumpGrace = 30;
            repathAfterLanding = false;
            peakFallDistance = 0;
            bridgeTicksLeft = 0;
            updateOverlay(player);
            return;
        }

        // Pick up drops (especially after mining) — walk onto ItemEntity
        if (tickScoopDrops(mc, level, player)) {
            return;
        }

        // Dangerous drop ahead → bridge if we have blocks, else stop & repath
        if (bridgeTicksLeft > 0 || shouldBridgeGap(level, player)) {
            if (BotUtil.hasScaffoldBlock(player)) {
                if (bridgeTicksLeft <= 0) {
                    bridgeTicksLeft = 35;
                }
                lastAnalysis = "місток (без шкоди від падіння)";
                BotOverlay.addPlace(player.blockPosition().relative(player.getDirection()).below());
                BotUtil.tickBridgeForward(mc, player);
                bridgeTicksLeft--;
                int depth = BotUtil.fallDepthAhead(level, player, 12);
                if (depth <= 3 || bridgeTicksLeft <= 0) {
                    bridgeTicksLeft = 0;
                    repath(level, player, false);
                    repathCooldown = 15;
                    noJumpGrace = 10;
                }
                updateOverlay(player);
                return;
            }
            bridgeTicksLeft = 0;
            lastAnalysis = "обрив без блоків — обхід";
            MovementKeys.setMove(false, false, false, false);
            repath(level, player, true);
            repathCooldown = 40;
            stuckTicks = 5;
            updateOverlay(player);
            return;
        }

        // Mid tower climb
        if (pillarTicksLeft > 0) {
            if (player.getY() >= pillarTargetY - 0.15 || pillarTicksLeft <= 1) {
                pillarTicksLeft = 0;
                BotControl.clearLookLock();
            } else if (BotUtil.hasScaffoldBlock(player)) {
                lastAnalysis = "ставлю блок під себе";
                BotOverlay.addPlace(player.blockPosition().relative(player.getDirection()));
                BotUtil.tickTowerUp(mc, player);
                pillarTicksLeft--;
                updateOverlay(player);
                return;
            } else {
                pillarTicksLeft = 0;
            }
        }

        // Dig only after real stuck — prefer repath around first (wider A*)
        boolean wantDig = digTarget != null
                || (moveGrace <= 0 && player.horizontalCollision && stuckTicks > 35);
        if (wantDig) {
            // Try repath around obstacle before tunneling a 1-block hole
            if (digTarget == null && stuckTicks > 35 && stuckTicks < 55) {
                repath(level, player, false);
                repathCooldown = 25;
                stuckTicks = 20;
                lastAnalysis = "обхід замість копання";
                updateOverlay(player);
                // fall through to walk with new path
            } else {
                if (digTarget == null) {
                    digTarget = chooseDigTarget(level, player);
                }
                if (digTarget != null && !level.getBlockState(digTarget).isAir()) {
                    // Don't dig deeper into a 1-wide squeeze if we can widen
                    if (SurvivalAStar.isOneBlockWideCorridor(level, player.blockPosition())
                            && digCountThisStretch > 6) {
                        digTarget = null;
                        digCountThisStretch = 0;
                        repath(level, player, true);
                        repathCooldown = 30;
                        stuckTicks = 10;
                        lastAnalysis = "занадто вузько — перебудова шляху";
                        updateOverlay(player);
                    } else if (!BotUtil.canReachBlock(player, digTarget)) {
                        // Walk closer — never stand still "mining" air at range
                        lastAnalysis = "підходжу до блоку";
                        lookAt(player, Vec3.atCenterOf(digTarget));
                        MovementKeys.setMove(true, player.horizontalCollision, false, true);
                        if (player.horizontalCollision) {
                            stuckTicks += 2;
                        }
                        // Give up if still unreachable while stuck
                        if (stuckTicks > 80 || BotUtil.eyeDistToBlock(player, digTarget) > 8) {
                            digTarget = null;
                            BotUtil.stopMining(mc);
                            repath(level, player, false);
                            stuckTicks = 15;
                        }
                        updateOverlay(player);
                        return;
                    } else {
                        lastAnalysis = "копаю " + digTarget.toShortString();
                        MovementKeys.setMove(true, false, false, true);
                        BotOverlay.addBreak(digTarget);
                        if (BotUtil.mineTick(mc, player, digTarget)) {
                            BotOverlay.clearBreak();
                            digTarget = null;
                            stuckTicks = 0;
                            digCountThisStretch++;
                            scoopTicks = 55;
                            BotUtil.stopMining(mc);
                        }
                        updateOverlay(player);
                        return;
                    }
                } else {
                    digTarget = null;
                    BotUtil.stopMining(mc);
                }
            }
        } else if (BotUtil.getMiningPos() != null) {
            BotUtil.stopMining(mc);
            digCountThisStretch = 0;
        }

        if (repathCooldown > 0) {
            repathCooldown--;
        } else if (walkPath.isEmpty() || walkIndex >= walkPath.size() || stuckTicks > 25) {
            repath(level, player, stuckTicks > 25 || walkPath.isEmpty());
            repathCooldown = 35;
            if (stuckTicks > 25) {
                stuckTicks = 10;
            }
        }

        BlockPos next;
        if (!walkPath.isEmpty() && walkIndex < walkPath.size()) {
            next = walkPath.get(walkIndex);
            Vec3 nextVec = SurvivalAStar.toVec(next);
            if (player.position().distanceTo(nextVec) < 1.2) {
                walkIndex++;
                stuckTicks = Math.max(0, stuckTicks - 8);
                if (walkIndex >= walkPath.size()) {
                    repath(level, player, false);
                    repathCooldown = 20;
                    if (walkPath.isEmpty()) {
                        next = BlockPos.containing(target.x, player.getY(), target.z);
                    } else {
                        walkIndex = 0;
                        next = walkPath.getFirst();
                    }
                } else {
                    next = walkPath.get(walkIndex);
                }
            }
        } else {
            next = BlockPos.containing(target.x, player.getY(), target.z);
        }

        // Stale high path node after fall / cliff — don't jump at sky, repath
        BlockPos feetNow = player.blockPosition();
        int riseToNext = next.getY() - feetNow.getY();
        if (riseToNext >= 2 && feetNow.distManhattan(next) > 2) {
            lastAnalysis = "ціль шляху зависоко — перебудова";
            repath(level, player, true);
            repathCooldown = 25;
            noJumpGrace = 20;
            MovementKeys.setMove(true, false, false, true);
            updateOverlay(player);
            return;
        }

        // === Baritone prepared(): dig positionsToBreak for this step BEFORE walking ===
        PathMoves.Step step = PathMoves.stepToward(feetNow, next);
        List<BlockPos> needBreak = PathMoves.remainingBreaks(level, step.toBreak());
        BotOverlay.setBreakBlocks(new java.util.LinkedHashSet<>(needBreak));
        if (!needBreak.isEmpty()) {
            if (tickPrepareBreak(mc, level, player, needBreak)) {
                updateOverlay(player);
                return;
            }
        }

        // Head blocked above / bumping ceiling — dig instead of jump spam
        if (tickHeadClear(mc, level, player)) {
            updateOverlay(player);
            return;
        }

        BlockPos walkAim = step.dest();
        // Pillar only for 2+ rise adjacent
        if (handleTwoBlockClimb(mc, level, player, walkAim.equals(feetNow) ? next : walkAim)) {
            updateOverlay(player);
            return;
        }

        // deep water ahead → boat
        if (BotUtil.hasBoatItem(player) && deepWaterAhead(level, player, next)) {
            waterMode = WaterMode.PLACE;
            waterTicks = 0;
            BlockPos water = findWaterNear(level, player.blockPosition(), 4);
            if (water != null) {
                BotOverlay.addPlace(water);
            }
            MovementKeys.setMove(true, false, false, true);
            chat(mc, "§bВода попереду — ставлю човен");
            updateOverlay(player);
            return;
        }

        // Baritone moveTowards only after prepared — yaw to dest, keep pitch
        BotControl.moveTowards(player, walkAim);

        int rise = walkAim.getY() - feetNow.getY();
        boolean canStep = rise == 1 && PathMoves.remainingBreaks(level, step.toBreak()).isEmpty()
                && (SurvivalAStar.canTraverse(level, feetNow, walkAim)
                || SurvivalAStar.canStandAt(level, walkAim));
        boolean alignedForStep = feetNow.getX() == walkAim.getX() || feetNow.getZ() == walkAim.getZ();
        boolean needJump = noJumpGrace <= 0 && canStep && alignedForStep
                && step.kind() == PathMoves.Kind.ASCEND;

        MovementKeys.setMove(true, needJump, false, true);

        if (player.onGround() && player.horizontalCollision) {
            stuckTicks += 3;
        } else if (player.horizontalCollision) {
            stuckTicks += 1;
        } else {
            stuckTicks = Math.max(0, stuckTicks - 2);
        }

        if (stuckTicks > 100) {
            chat(mc, "§cЗастряг пішки. Копаю / перебудовую…");
            BlockPos front = blockInFront(level, player);
            digTarget = (front != null && BotUtil.canReachBlock(player, front)) ? front : null;
            repath(level, player, true);
            repathCooldown = 25;
            stuckTicks = 20;
            noJumpGrace = 15;
            MovementKeys.setMove(true, true, false, true);
        }
        if (stuckTicks > 250) {
            chat(mc, "§cВажко пройти в survival. " + lastAnalysis);
            stuckTicks = 120;
        }
        updateOverlay(player);
    }

    /**
     * Baritone {@code Movement.prepared}: dig each positionsToBreak in order until clear.
     * @return true if this tick was consumed (still mining)
     */
    private boolean tickPrepareBreak(Minecraft mc, ClientLevel level, LocalPlayer player, List<BlockPos> needBreak) {
        BlockPos dig = null;
        for (BlockPos p : needBreak) {
            if (!SurvivalAStar.isAirLike(level, p) && BotUtil.canReachBlock(player, p)) {
                dig = p;
                break;
            }
        }
        if (dig == null) {
            // Something to break but out of reach — walk closer with yaw only
            BlockPos first = needBreak.getFirst();
            lastAnalysis = "підходжу копати " + first.toShortString();
            BotControl.moveTowards(player, first);
            MovementKeys.setMove(true, false, false, true);
            if (BotUtil.eyeDistToBlock(player, first) > 8) {
                digTarget = null;
                repath(level, player, false);
            }
            return true;
        }
        digTarget = dig;
        lastAnalysis = "підготовка кроку — копаю " + dig.toShortString();
        MovementKeys.setMove(false, false, false, false);
        BotOverlay.addBreak(dig);
        if (BotUtil.mineTick(mc, player, dig)) {
            scoopTicks = 35;
            if (PathMoves.remainingBreaks(level, needBreak).isEmpty()) {
                digTarget = null;
                BotUtil.stopMining(mc);
            }
        }
        return true;
    }

    /** Walk onto nearby item entities so vanilla pickup works. */
    private boolean tickScoopDrops(Minecraft mc, ClientLevel level, LocalPlayer player) {
        double range = scoopTicks > 0 ? 5.5 : 2.4;
        ItemEntity drop = BotUtil.nearestItemDrop(level, player, range);
        if (drop == null) {
            if (scoopTicks > 0) {
                scoopTicks--;
            }
            return false;
        }
        // Always scoop close drops; after mining use wider radius
        if (scoopTicks <= 0 && player.distanceTo(drop) > 2.4) {
            return false;
        }
        lastAnalysis = "підбір " + drop.getItem().getHoverName().getString();
        lookAt(player, drop.position().add(0, 0.2, 0));
        double d = player.distanceTo(drop);
        if (d > 0.95) {
            MovementKeys.setMove(true, player.horizontalCollision, false, false);
        } else {
            MovementKeys.setMove(false, false, false, false);
            if (scoopTicks > 0) {
                scoopTicks--;
            }
        }
        if (scoopTicks > 0) {
            scoopTicks--;
        }
        return true;
    }

    /**
     * If next cell is 2+ up or a 2-high wall is in front: dig soft blocks or tower with scaffold.
     * Player is always treated as 2 blocks tall — never "walk through" a 1-high hole by jumping.
     * @return true if this tick was consumed
     */
    private boolean handleTwoBlockClimb(Minecraft mc, ClientLevel level, LocalPlayer player, BlockPos next) {
        BlockPos feet = player.blockPosition();
        int rise = next.getY() - feet.getY();
        // Cardinal only — never dig/check the diagonal cell
        BlockPos step = BotUtil.cardinalStepToward(feet, next);
        int dx = step.getX() - feet.getX();
        int dz = step.getZ() - feet.getZ();
        if (dx == 0 && dz == 0) {
            Direction dir = player.getDirection();
            dx = dir.getStepX();
            dz = dir.getStepZ();
        }
        BlockPos f0 = feet.offset(dx, 0, dz);
        BlockPos f1 = f0.above();
        boolean solid0 = needsBreak(level, f0);
        boolean solid1 = needsBreak(level, f1);
        boolean twoHighWall = solid0 && solid1;
        boolean adjacentHigh = rise >= 2 && feet.distManhattan(next) <= 2;
        boolean highStep = adjacentHigh;

        // 1-block step-up (onto f0): only if landing fits 2-tall + jump headroom
        if (solid0 && !solid1 && rise == 1
                && SurvivalAStar.canTraverseStepUp(level, feet, f0, f0.above())) {
            return false; // let jump handle it
        }

        // Flat walk blocked by body and/or head — dig for 2-tall passage (do NOT jump)
        if (rise <= 0 && (solid0 || solid1) && feet.distManhattan(f0) <= 2) {
            return digTwoTallPassage(mc, level, player, f0, f1, solid0, solid1);
        }

        // Head-only block while colliding
        if (!solid0 && solid1 && (player.horizontalCollision || rise >= 0)) {
            if (!BotUtil.canReachBlock(player, f1)) {
                return false;
            }
            digTarget = f1;
            lastAnalysis = "копаю (гравець 2 блоки висотою)";
            MovementKeys.setMove(true, false, false, true);
            BotOverlay.addBreak(f1);
            if (BotUtil.mineTick(mc, player, f1)) {
                digTarget = null;
                scoopTicks = 45;
                BotUtil.stopMining(mc);
            }
            return true;
        }

        if (!twoHighWall && !highStep) {
            return false;
        }

        if (player.distanceToSqr(f0.getX() + 0.5, player.getY(), f0.getZ() + 0.5) > 12) {
            return false;
        }

        float digCost = 0f;
        if (solid0) {
            digCost += BotUtil.estimateBreakTicks(mc, player, level, f0);
        }
        if (solid1) {
            digCost += BotUtil.estimateBreakTicks(mc, player, level, f1);
        }
        if (!solid0 && !solid1 && highStep) {
            digCost = 1_000_000f;
        }

        boolean canPillar = BotUtil.hasScaffoldBlock(player);
        float pillarCost = canPillar ? Math.max(2, rise) * 14f : 1_000_000f;

        if (canPillar && pillarCost <= digCost) {
            lastAnalysis = "підйом: ставлю блоки";
            pillarTargetY = Math.max(player.getY() + 1.9, next.getY() + 0.1);
            pillarTicksLeft = 20 * Math.max(2, Math.min(6, rise + 1));
            BotOverlay.addPlace(feet.relative(player.getDirection()));
            BotUtil.tickTowerUp(mc, player);
            return true;
        }

        if (solid0 || solid1) {
            return digTwoTallPassage(mc, level, player, f0, f1, solid0, solid1);
        }

        if (canPillar) {
            lastAnalysis = "підйом блоками";
            pillarTargetY = next.getY() + 0.1;
            pillarTicksLeft = 40;
            BotUtil.tickTowerUp(mc, player);
            return true;
        }

        return false;
    }

    /** Dig body then head so a 2-tall player can walk through. */
    private boolean digTwoTallPassage(Minecraft mc, ClientLevel level, LocalPlayer player,
                                      BlockPos f0, BlockPos f1, boolean solid0, boolean solid1) {
        digTarget = solid0 ? f0 : f1;
        if (!BotUtil.canReachBlock(player, digTarget)) {
            digTarget = null;
            return false;
        }
        lastAnalysis = "проход 2×1 — копаю";
        MovementKeys.setMove(true, false, false, true);
        BotOverlay.addBreak(f0);
        BotOverlay.addBreak(f1);
        if (BotUtil.mineTick(mc, player, digTarget)) {
            scoopTicks = 50;
            if (digTarget.equals(f0) && (solid1 || needsBreak(level, f1))) {
                digTarget = f1;
            } else {
                digTarget = null;
                BotUtil.stopMining(mc);
            }
        }
        return true;
    }

    /** Clear whatever blocks a 2-tall body from reaching {@code next}. */
    private boolean tickClearTwoTallFront(Minecraft mc, ClientLevel level, LocalPlayer player, BlockPos next) {
        BlockPos feet = player.blockPosition();
        BlockPos step = BotUtil.cardinalStepToward(feet, next);
        Direction dir;
        int dx = step.getX() - feet.getX();
        int dz = step.getZ() - feet.getZ();
        if (dx != 0 || dz != 0) {
            dir = Direction.getApproximateNearest(dx, 0, dz);
        } else {
            dir = player.getDirection();
        }
        if (dir.getAxis() == Direction.Axis.Y) {
            dir = player.getDirection();
        }
        var blockers = SurvivalAStar.blockingForTwoTall(level, feet, dir);
        if (blockers.isEmpty() && next.getY() == feet.getY() + 1) {
            // Invalid step-up (no headroom) — dig ceiling above player
            BlockPos ceil = feet.above(SurvivalAStar.PLAYER_HEIGHT);
            if (needsBreak(level, ceil) && BotUtil.canReachBlock(player, ceil)) {
                digTarget = ceil;
                lastAnalysis = "копаю стелю для стрибка";
                MovementKeys.setMove(false, false, false, false);
                BotOverlay.addBreak(ceil);
                if (BotUtil.mineTick(mc, player, ceil)) {
                    digTarget = null;
                    BotUtil.stopMining(mc);
                }
                return true;
            }
            return false;
        }
        if (blockers.isEmpty()) {
            return false;
        }
        BlockPos dig = blockers.getFirst();
        if (!BotUtil.canReachBlock(player, dig)) {
            return false;
        }
        digTarget = dig;
        lastAnalysis = "очищаю прохід (2 блоки висоти)";
        MovementKeys.setMove(true, false, false, true);
        for (BlockPos b : blockers) {
            BotOverlay.addBreak(b);
        }
        if (BotUtil.mineTick(mc, player, dig)) {
            digTarget = null;
            scoopTicks = 40;
            BotUtil.stopMining(mc);
        }
        return true;
    }

    private void updateOverlay(LocalPlayer player) {
        if (walkPath.isEmpty()) {
            List<BlockPos> approx = new ArrayList<>();
            if (target != null) {
                approx.add(player.blockPosition());
                approx.add(BlockPos.containing(target));
            }
            BotOverlay.setPath(approx, target);
        } else {
            int from = Math.max(0, walkIndex);
            BotOverlay.setPath(walkPath.subList(from, walkPath.size()), target);
        }
    }

    private void refreshBreakPreview(ClientLevel level, LocalPlayer player, BlockPos next) {
        java.util.Set<BlockPos> preview = new java.util.LinkedHashSet<>();
        BlockPos feet = player.blockPosition();
        // Only look 1–3 steps ahead — don't mark a long tunnel to dig
        int steps = Math.max(1, Math.min(3, (int) Math.ceil(Math.sqrt(feet.distSqr(next)))));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            int x = feet.getX() + (int) Math.round((next.getX() - feet.getX()) * t);
            int y = feet.getY() + (int) Math.round((next.getY() - feet.getY()) * t);
            int z = feet.getZ() + (int) Math.round((next.getZ() - feet.getZ()) * t);
            BlockPos body = new BlockPos(x, y, z);
            BlockPos head = body.above();
            if (needsBreak(level, body)) {
                preview.add(body);
            }
            if (needsBreak(level, head)) {
                preview.add(head);
            }
        }
        if (digTarget != null) {
            preview.add(digTarget);
        }
        BotOverlay.setBreakBlocks(preview);
    }

    private static boolean needsBreak(ClientLevel level, BlockPos pos) {
        var st = level.getBlockState(pos);
        return !st.isAir() && !st.getCollisionShape(level, pos).isEmpty()
                && !st.getFluidState().is(FluidTags.WATER);
    }

    /** @return true if boat logic consumed the tick */
    private boolean tickBoat(Minecraft mc, ClientLevel level, LocalPlayer player) {
        if (waterMode == WaterMode.NONE && !(player.getVehicle() instanceof AbstractBoat)) {
            return false;
        }
        waterTicks++;
        lastAnalysis = "човен:" + waterMode;

        if (waterMode == WaterMode.PLACE) {
            MovementKeys.setMove(false, false, false, false);
            if (player.getVehicle() instanceof AbstractBoat) {
                waterMode = WaterMode.SAIL;
                return true;
            }
            AbstractBoat near = BotUtil.nearestBoat(level, player, 4);
            if (near != null) {
                BotUtil.interactEntity(mc, player, near);
                return true;
            }
            if (!BotUtil.selectBoat(mc, player)) {
                waterMode = WaterMode.NONE;
                return false;
            }
            // place on water in front
            Vec3 look = player.getLookAngle();
            BlockPos water = BlockPos.containing(player.position().add(look.x * 2, 0, look.z * 2));
            if (!level.getFluidState(water).is(FluidTags.WATER)) {
                water = findWaterNear(level, player.blockPosition(), 4);
            }
            if (water != null) {
                lookAt(player, Vec3.atCenterOf(water));
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(water), Direction.UP, water, false);
                mc.gameMode.useItemOn(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            if (waterTicks > 80) {
                waterMode = WaterMode.NONE;
            }
            return true;
        }

        if (waterMode == WaterMode.SAIL || player.getVehicle() instanceof AbstractBoat) {
            waterMode = WaterMode.SAIL;
            lookAt(player, target);
            MovementKeys.setMove(true, false, false, true);
            // land nearby or shallow — dismount
            BlockPos feet = player.blockPosition();
            boolean landNear = !level.getFluidState(feet).is(FluidTags.WATER)
                    && !level.getFluidState(feet.below()).is(FluidTags.WATER)
                    && player.onGround();
            boolean goalLand = distToGoalLand(level, player);
            if ((landNear && waterTicks > 40) || goalLand || waterTicks > 20 * 90) {
                MovementKeys.setMove(false, false, true, false); // sneak dismount
                player.stopRiding();
                waterMode = WaterMode.RECOVER;
                waterTicks = 0;
            }
            return true;
        }

        if (waterMode == WaterMode.RECOVER) {
            MovementKeys.setMove(false, false, false, false);
            AbstractBoat boat = BotUtil.nearestBoat(level, player, 6);
            if (boat != null) {
                BotUtil.selectBestToolFor(mc, player, level.getBlockState(BlockPos.ZERO)); // axe prefer
                BotUtil.selectItem(mc, player, s -> s.is(net.minecraft.world.item.Items.IRON_AXE)
                        || s.is(net.minecraft.world.item.Items.STONE_AXE)
                        || s.is(net.minecraft.world.item.Items.WOODEN_AXE));
                BotUtil.lookAtEntity(player, boat);
                if (player.distanceTo(boat) <= 3.5 && mc.gameMode != null) {
                    mc.gameMode.attack(player, boat);
                    player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                } else {
                    MovementKeys.setMove(true, false, false, true);
                }
            } else {
                // boat item should be picked up by walking; done
                waterMode = WaterMode.NONE;
                chat(mc, "§bЧовен забрав — далі пішки");
                repath(level, player, false);
            }
            if (waterTicks > 100) {
                waterMode = WaterMode.NONE;
            }
            return true;
        }
        return false;
    }

    private boolean distToGoalLand(ClientLevel level, LocalPlayer player) {
        if (target == null) {
            return false;
        }
        if (player.position().distanceTo(target) > 16) {
            return false;
        }
        BlockPos t = BlockPos.containing(target);
        return !level.getFluidState(t).is(FluidTags.WATER);
    }

    private boolean deepWaterAhead(ClientLevel level, LocalPlayer player, BlockPos next) {
        Vec3 look = player.getLookAngle();
        for (int i = 1; i <= 6; i++) {
            BlockPos p = BlockPos.containing(player.position().add(look.x * i, 0, look.z * i));
            if (level.getFluidState(p).is(FluidTags.WATER) && level.getFluidState(p.below()).is(FluidTags.WATER)) {
                return true;
            }
        }
        return level.getFluidState(next).is(FluidTags.WATER) && level.getFluidState(next.below()).is(FluidTags.WATER);
    }

    private BlockPos findWaterNear(ClientLevel level, BlockPos origin, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos p = origin.offset(dx, 0, dz);
                if (level.getFluidState(p).is(FluidTags.WATER)) {
                    return p;
                }
            }
        }
        return null;
    }

    private BlockPos blockInFront(ClientLevel level, LocalPlayer player) {
        Vec3 look = player.getLookAngle();
        BlockPos p = BlockPos.containing(player.getEyePosition().add(look.scale(1.5)));
        BlockState st = level.getBlockState(p);
        if (!st.isAir() && !st.getCollisionShape(level, p).isEmpty()) {
            return p;
        }
        p = player.blockPosition().relative(player.getDirection());
        st = level.getBlockState(p);
        if (!st.isAir() && !st.getCollisionShape(level, p).isEmpty()) {
            return p;
        }
        p = p.above();
        st = level.getBlockState(p);
        if (!st.isAir() && !st.getCollisionShape(level, p).isEmpty()) {
            return p;
        }
        return null;
    }

    private void tickFly(ClientLevel level, LocalPlayer player, double dist) {
        Vec3 desired = pickSteerDirection(level, player, target);
        lookAt(player, player.position().add(desired.scale(8)));
        applyFlyMovement(player, desired, dist);

        if (player.horizontalCollision || player.verticalCollision) {
            stuckTicks++;
        } else if (player.getDeltaMovement().horizontalDistanceSqr() > 0.01) {
            stuckTicks = Math.max(0, stuckTicks - 2);
        } else {
            stuckTicks++;
        }
        if (stuckTicks > 80) {
            applyFlyMovement(player, new Vec3(desired.x, 1.0, desired.z).normalize(), dist);
            if (stuckTicks > 200) {
                stuckTicks = 100;
            }
        }
    }

    private void repath(ClientLevel level, LocalPlayer player, boolean forceMsg) {
        walkPath.clear();
        walkIndex = 0;
        digCountThisStretch = 0;
        BlockPos start = BlockPos.containing(player.getX(), Math.floor(player.getY()), player.getZ());
        BlockPos goal = BlockPos.containing(target.x, target.y, target.z);

        // Wider local A* so we see around obstacles instead of digging a 1-block hole
        // Cheat: use full loaded-chunk awareness with a larger search budget
        int required = start.distManhattan(goal);
        // Performance: even in cheat mode, don't expand path search to the full scan radius.
        // Keep it tied to "how far" the goal is, with a small margin.
        int range = BotCheat.isEnabled()
                ? Math.min(192, Math.max(96, (int) Math.ceil(required) + 12))
                : 96;
        List<BlockPos> path = SurvivalAStar.findPath(level, start, goal, range);
        if (!path.isEmpty()) {
            walkPath.addAll(path);
            if (forceMsg) {
                chat(Minecraft.getInstance(), "§7Піший шлях: " + walkPath.size() + " кроків (A* " + range + "м)");
            }
        }
    }

    private BlockPos chooseDigTarget(ClientLevel level, LocalPlayer player) {
        BlockPos front = blockInFront(level, player);
        BlockPos feet = player.blockPosition();
        Direction dir = player.getDirection();

        if (SurvivalAStar.isOneBlockWideCorridor(level, feet)) {
            BlockPos left = feet.relative(dir.getCounterClockWise());
            BlockPos right = feet.relative(dir.getClockWise());
            if (needsBreak(level, left) && BotUtil.canReachBlock(player, left)) {
                return left;
            }
            if (needsBreak(level, right) && BotUtil.canReachBlock(player, right)) {
                return right;
            }
            if (needsBreak(level, left.above()) && BotUtil.canReachBlock(player, left.above())) {
                return left.above();
            }
            if (needsBreak(level, right.above()) && BotUtil.canReachBlock(player, right.above())) {
                return right.above();
            }
        }

        if (front != null && BotUtil.canReachBlock(player, front)) {
            return front;
        }
        BlockPos f0 = feet.relative(dir);
        if (needsBreak(level, f0) && BotUtil.canReachBlock(player, f0)) {
            return f0;
        }
        if (needsBreak(level, f0.above()) && BotUtil.canReachBlock(player, f0.above())) {
            return f0.above();
        }
        return null;
    }

    private boolean gapAhead(ClientLevel level, LocalPlayer player) {
        Vec3 look = player.getLookAngle();
        BlockPos ahead = BlockPos.containing(player.position().add(look.x * 1.2, -0.1, look.z * 1.2));
        return !SurvivalAStar.canStandAt(level, ahead) && SurvivalAStar.canStandAt(level, ahead.below());
    }

    /** True when stepping forward would deal fall damage — prefer bridge. */
    private boolean shouldBridgeGap(ClientLevel level, LocalPlayer player) {
        if (moveGrace > 0 || !player.onGround()) {
            return false;
        }
        // Path next cell is a damaging drop
        if (!walkPath.isEmpty() && walkIndex < walkPath.size()) {
            BlockPos next = walkPath.get(walkIndex);
            int drop = player.blockPosition().getY() - next.getY();
            if (drop >= 4 && player.blockPosition().distManhattan(next) <= 2) {
                return true;
            }
        }
        int ahead = BotUtil.fallDepthAhead(level, player, 12);
        return ahead >= 4;
    }

    /**
     * If ceiling / head-height block is in the way — dig it. Stops jump-into-ceiling.
     */
    private boolean tickHeadClear(Minecraft mc, ClientLevel level, LocalPlayer player) {
        BlockPos feet = player.blockPosition();
        BlockPos ceiling = feet.above(2);
        BlockPos headFront = feet.relative(player.getDirection()).above();
        BlockPos bodyFront = feet.relative(player.getDirection());

        boolean bumpCeiling = player.verticalCollision && !player.onGround()
                && needsBreak(level, ceiling);
        boolean headWall = player.horizontalCollision
                && needsBreak(level, headFront)
                && !needsBreak(level, bodyFront);
        boolean stuckJumpingUp = stuckTicks > 20 && player.horizontalCollision
                && needsBreak(level, headFront);

        BlockPos dig = null;
        if (bumpCeiling && BotUtil.canReachBlock(player, ceiling)) {
            dig = ceiling;
        } else if ((headWall || stuckJumpingUp) && BotUtil.canReachBlock(player, headFront)) {
            dig = headFront;
        }
        if (dig == null) {
            return false;
        }
        digTarget = dig;
        lastAnalysis = "копаю блок над головою";
        MovementKeys.setMove(true, false, false, true);
        BotOverlay.addBreak(dig);
        if (BotUtil.mineTick(mc, player, dig)) {
            digTarget = null;
            scoopTicks = 40;
            BotUtil.stopMining(mc);
            stuckTicks = 0;
        }
        return true;
    }

    private boolean useWalk(LocalPlayer player) {
        if (mode == Mode.FORCE_WALK) {
            return true;
        }
        if (mode == Mode.FORCE_FLY) {
            return false;
        }
        if (player.isSpectator()) {
            return false;
        }
        if (player.getAbilities().flying) {
            return false;
        }
        var gt = player.gameMode();
        if (gt != null && gt.isCreative()) {
            return false; // creative: enable fly steering
        }
        return true; // survival / adventure
    }

    private void finishArrive(Minecraft mc, LocalPlayer player) {
        MovementKeys.setMove(false, false, false, false);
        player.setDeltaMovement(Vec3.ZERO);
        BotUtil.stopMining(mc);
        BotOverlay.clearAll();
        chat(mc, "§aПрибув: " + fmt(target));
        Runnable cb = onArrive;
        onArrive = null;
        releaseControl(player);
        state = State.IDLE;
        walkPath.clear();
        digTarget = null;
        if (cb != null) {
            cb.run();
        }
    }

    public void analyze(Minecraft mc, LocalPlayer player) {
        if (target == null || mc.level == null) {
            lastAnalysis = "нема цілі";
            return;
        }
        double dist = player.position().distanceTo(target);
        boolean walk = useWalk(player);
        double speed = walk ? 4.3 : Math.max(0.5, flySpeed * 12.0);
        int etaSec = (int) (dist / speed);
        String eta = etaSec > 3600
                ? String.format("%.1fгод", etaSec / 3600.0)
                : (etaSec > 60 ? String.format("%dхв", etaSec / 60) : etaSec + "с");

        if (walk) {
            lastAnalysis = String.format("пішки dist=%.0f ETA~%s path=%d/%d",
                    dist, eta, Math.min(walkIndex, walkPath.size()), walkPath.size());
            if (dist > 2000) {
                lastAnalysis += " | далеко для survival — буде довго";
            }
        } else {
            Vec3 eye = player.getEyePosition();
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            boolean clear = hit.getType() == HitResult.Type.MISS;
            lastAnalysis = String.format("політ dist=%.0f ETA~%s %s", dist, eta, clear ? "чисто" : "обхід");
        }
    }

    private Vec3 pickSteerDirection(ClientLevel level, LocalPlayer player, Vec3 goal) {
        Vec3 pos = player.position().add(0, 1.0, 0);
        Vec3 direct = goal.subtract(pos);
        if (direct.lengthSqr() < 1.0E-6) {
            return Vec3.ZERO;
        }
        Vec3 best = direct.normalize();
        double bestScore = scoreDirection(level, player, pos, best, goal);
        double[] pitches = {0, -0.4, -0.8, 0.3, 0.6, 1.0};
        double[] yaws = {0, 0.4, -0.4, 0.9, -0.9, 1.4, -1.4};
        for (double yawOff : yaws) {
            for (double pitchOff : pitches) {
                Vec3 dir = rotateToward(direct.normalize(), yawOff, pitchOff);
                double score = scoreDirection(level, player, pos, dir, goal);
                if (score > bestScore) {
                    bestScore = score;
                    best = dir;
                }
            }
        }
        return best;
    }

    private static Vec3 rotateToward(Vec3 base, double yawOff, double pitchOff) {
        double yaw = Math.atan2(base.z, base.x) + yawOff;
        double horiz = Math.sqrt(base.x * base.x + base.z * base.z);
        double pitch = Math.atan2(base.y, horiz) + pitchOff;
        double ch = Math.cos(pitch);
        return new Vec3(Math.cos(yaw) * ch, Math.sin(pitch), Math.sin(yaw) * ch).normalize();
    }

    private double scoreDirection(ClientLevel level, LocalPlayer player, Vec3 from, Vec3 dir, Vec3 goal) {
        double approach = dir.dot(goal.subtract(from).normalize());
        double clearance = 0;
        for (int i = 1; i <= 12; i++) {
            Vec3 p = from.add(dir.scale(i * 1.5));
            BlockPos bp = BlockPos.containing(p);
            if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                clearance -= (13 - i) * 3.0;
                break;
            }
            clearance += 1.0;
        }
        if (dir.y > 0.2) {
            clearance += 0.5;
        }
        return approach * 10.0 + clearance;
    }

    private void applyFlyMovement(LocalPlayer player, Vec3 dir, double dist) {
        if (player.getAbilities().mayfly && !player.getAbilities().flying) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
        boolean up = dir.y > 0.25;
        boolean down = dir.y < -0.25;
        MovementKeys.setMove(true, up, down, true);
        double speed = Math.max(0.4, flySpeed * 12.0);
        if (dist < 12) {
            speed *= dist / 12.0;
        }
        player.setDeltaMovement(dir.scale(speed));
    }

    private void takeControl(LocalPlayer player) {
        if (controlling) {
            return;
        }
        controlling = true;
        MovementKeys.ensureKeyboardInput();
        MovementKeys.setMove(false, false, false, false);
        if (!useWalk(player) && player.getAbilities().mayfly) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            savedFlySpeed = player.getAbilities().getFlyingSpeed();
            player.getAbilities().setFlyingSpeed(flySpeed);
        }
    }

    private void releaseControl(LocalPlayer player) {
        if (!controlling) {
            MovementKeys.clear();
            return;
        }
        controlling = false;
        BotUtil.stopMining(Minecraft.getInstance());
        BotControl.clearLookLock();
        MovementKeys.clear();
        MovementKeys.ensureKeyboardInput();
        if (savedFlySpeed != null && player != null) {
            player.getAbilities().setFlyingSpeed(savedFlySpeed);
            savedFlySpeed = null;
        }
    }

    private static void lookAt(LocalPlayer player, Vec3 target) {
        BotControl.lookAt(player, target, true);
    }

    private static String fmt(Vec3 v) {
        return String.format("%.0f %.0f %.0f", v.x, v.y, v.z);
    }

    private static void chat(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
