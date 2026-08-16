package com.lecternscanner.client.logic;

import java.util.Locale;

import com.lecternscanner.client.BotControl;
import com.lecternscanner.client.BotOverlay;
import com.lecternscanner.client.BotUtil;
import com.lecternscanner.client.CraftHelper;
import com.lecternscanner.client.MovementKeys;
import com.lecternscanner.client.PathNavigator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Runs a {@link LogicGraph} tick-by-tick (sequential + IF / radius search).
 */
public final class LogicExecutor {
    public enum Phase { IDLE, RUNNING, DONE, FAILED }

    private Phase phase = Phase.IDLE;
    private LogicGraph graph;
    private String currentId;
    private int ticksOnNode;
    private int nodeTimeout = 20 * 45;
    private final CraftHelper craft = new CraftHelper();
    private BlockPos workPos;
    private String status = "";
    private PathNavigator nav;
    /** Ticks left to prioritize walking onto drops after a break. */
    private int scoopTicks;
    private int placeCooldown;
    private int placeInvBefore = -1;
    /** Active work zone from AREA node; null = no limit. */
    private AABB workArea;

    public Phase getPhase() {
        return phase;
    }

    public boolean isActive() {
        return phase == Phase.RUNNING;
    }

    public String status() {
        return phase + (status.isEmpty() ? "" : (" | " + status));
    }

    public void start(LogicGraph graph, PathNavigator nav) {
        stop(false);
        this.graph = graph;
        this.nav = nav;
        this.phase = Phase.RUNNING;
        this.ticksOnNode = 0;
        this.workPos = null;
        this.scoopTicks = 0;
        this.placeCooldown = 0;
        this.placeInvBefore = -1;
        this.workArea = null;
        craft.abort(Minecraft.getInstance(), Minecraft.getInstance().player);
        graph.start().ifPresentOrElse(s -> {
            currentId = s.id;
            chat("§aНоди: старт схеми «" + graph.name + "»");
        }, () -> {
            phase = Phase.FAILED;
            chat("§cНема ноди Старт");
        });
    }

    public void stop(boolean announce) {
        phase = Phase.IDLE;
        currentId = null;
        workPos = null;
        scoopTicks = 0;
        placeCooldown = 0;
        placeInvBefore = -1;
        workArea = null;
        Minecraft mc = Minecraft.getInstance();
        craft.abort(mc, mc.player);
        BotUtil.stopMining(mc);
        MovementKeys.clear();
        BotOverlay.clearBreak();
        BotOverlay.clearPlace();
        if (announce) {
            chat("§eНоди зупинено");
        }
    }

    public void tick() {
        if (phase != Phase.RUNNING || graph == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            stop(true);
            return;
        }
        ticksOnNode++;
        if (ticksOnNode > nodeTimeout) {
            chat("§eТаймаут ноди — далі");
            advance(LogicEdge.Port.OUT);
            return;
        }

        LogicNode node = graph.find(currentId).orElse(null);
        if (node == null) {
            phase = Phase.DONE;
            chat("§aСхему нод завершено");
            stop(false);
            return;
        }
        status = node.title();
        player.displayClientMessage(Component.literal("§dНода§f " + status), true);

        switch (node.kind) {
            case START -> advance(LogicEdge.Port.OUT);
            case AREA -> runArea(node);
            case END -> {
                chat("§aРоботу завершено");
                phase = Phase.DONE;
                stop(false);
            }
            case IF -> runIf(mc, player, node);
            case HAS_ITEM -> branchBool(countTarget(player, node) >= Math.max(1, node.count));
            case HAS_NEAR, IN_RADIUS -> branchBool(findInRadius(mc, player, node) != null);
            case FIND_BLOCK -> runFind(mc, player, node);
            case MINE -> runMine(mc, player, node);
            case PICKUP -> runPickup(mc, player, node);
            case CRAFT -> runCraft(mc, player, node);
            case PLACE -> runPlace(mc, player, node);
            case GOTO -> runGoto(mc, player, node);
            case SMELT -> {
                chat("§eПереплавка: поки скіп (MVP)");
                advance(LogicEdge.Port.OUT);
            }
            case TAKE_FROM -> advance(LogicEdge.Port.OUT);
            default -> advance(LogicEdge.Port.OUT);
        }
    }

    private void runArea(LogicNode node) {
        workArea = resolveWorkArea(node);
        if (workArea == null) {
            chat("§eЗона: некоректні координати");
        } else {
            status = "зона " + formatArea(workArea);
            chat("§aЗона роботи: " + formatArea(workArea));
        }
        advance(LogicEdge.Port.OUT);
    }

    private static String formatArea(AABB a) {
        return String.format(Locale.ROOT, "%.0f,%.0f,%.0f … %.0f,%.0f,%.0f",
                a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ);
    }

    private static AABB resolveWorkArea(LogicNode node) {
        if ("box".equals(node.mode)) {
            int x1 = Math.min(node.posX, node.posX2);
            int y1 = Math.min(node.posY, node.posY2);
            int z1 = Math.min(node.posZ, node.posZ2);
            int x2 = Math.max(node.posX, node.posX2);
            int y2 = Math.max(node.posY, node.posY2);
            int z2 = Math.max(node.posZ, node.posZ2);
            return new AABB(x1, y1, z1, x2 + 1, y2 + 1, z2 + 1);
        }
        int r = Math.max(1, node.radius);
        double cx = node.posX + 0.5;
        double cy = node.posY + 0.5;
        double cz = node.posZ + 0.5;
        return new AABB(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r);
    }

    private boolean inWorkArea(BlockPos pos) {
        return workArea == null || workArea.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private void branchBool(boolean ok) {
        if (graph.out(currentId, LogicEdge.Port.TRUE).isPresent()
                || graph.out(currentId, LogicEdge.Port.FALSE).isPresent()) {
            advance(ok ? LogicEdge.Port.TRUE : LogicEdge.Port.FALSE);
        } else {
            advance(LogicEdge.Port.OUT);
        }
    }

    private void runIf(Minecraft mc, LocalPlayer player, LogicNode node) {
        String mode = node.mode == null ? "has_item" : node.mode;
        boolean ok = switch (mode) {
            case "has_near" -> findInRadius(mc, player, node) != null;
            case "has_count" -> countTarget(player, node) >= Math.max(1, node.count);
            default -> countTarget(player, node) >= Math.max(1, node.count);
        };
        branchBool(ok);
    }

    /** Search once in radius; TRUE if found (stores workPos), FALSE if not. */
    private void runFind(Minecraft mc, LocalPlayer player, LogicNode node) {
        BlockPos found = findInRadius(mc, player, node);
        if (found != null) {
            workPos = found;
            BotOverlay.addBreak(found);
            status = "знайдено " + node.shortTarget() + " @ " + found.toShortString();
            if (graph.out(node.id, LogicEdge.Port.TRUE).isPresent()
                    || graph.out(node.id, LogicEdge.Port.FALSE).isPresent()) {
                advance(LogicEdge.Port.TRUE);
            } else {
                advance(LogicEdge.Port.OUT);
            }
        } else {
            status = "нема " + node.shortTarget() + " у r=" + Math.max(4, node.radius);
            if (graph.out(node.id, LogicEdge.Port.TRUE).isPresent()
                    || graph.out(node.id, LogicEdge.Port.FALSE).isPresent()) {
                advance(LogicEdge.Port.FALSE);
            } else if (ticksOnNode > 20) {
                advance(LogicEdge.Port.OUT);
            }
        }
    }

    private void runGoto(Minecraft mc, LocalPlayer player, LogicNode node) {
        if (workPos == null) {
            workPos = findInRadius(mc, player, node);
        }
        if (workPos == null) {
            status = "нема цілі для GOTO";
            if (ticksOnNode > 40) {
                advance(LogicEdge.Port.OUT);
            }
            return;
        }
        if (player.blockPosition().closerThan(workPos, 2.5)) {
            if (nav != null) {
                nav.stop(false);
            }
            advance(LogicEdge.Port.OUT);
            return;
        }
        var center = net.minecraft.world.phys.Vec3.atCenterOf(workPos);
        if (nav != null) {
            if (!nav.isMoving()) {
                nav.goTo(center, null);
            }
            nav.tick();
        } else {
            BotUtil.lookAt(player, center);
            MovementKeys.setMove(true, false, false, true);
        }
    }

    private BlockPos findInRadius(Minecraft mc, LocalPlayer player, LogicNode node) {
        int range = Math.max(4, Math.min(64, node.radius));
        return BotUtil.findNearestBlock(mc.level, player.blockPosition(), range,
                st -> matchesBlock(st, node.target), workArea);
    }

    private void runMine(Minecraft mc, LocalPlayer player, LogicNode node) {
        if (countTarget(player, node) >= Math.max(1, node.count)) {
            // still scoop leftover drops before leaving
            if (node.autoPickup && tickScoop(mc, player, node, 5.5)) {
                status = "підбір…";
                return;
            }
            workPos = null;
            BotUtil.stopMining(mc);
            advance(LogicEdge.Port.OUT);
            return;
        }
        // Prefer pickup right after a break so drops don't despawn / get left behind
        if (node.autoPickup && (scoopTicks > 0 || BotUtil.nearestItemDrop(mc.level, player, 3.2) != null)) {
            if (tickScoop(mc, player, node, scoopTicks > 0 ? 5.5 : 3.2)) {
                status = "підбір…";
                return;
            }
        }
        int range = Math.max(8, node.radius);
        if (workPos == null || mc.level.getBlockState(workPos).isAir() || !inWorkArea(workPos)) {
            workPos = BotUtil.findNearestBlock(mc.level, player.blockPosition(), range,
                    st -> matchesBlock(st, node.target), workArea);
        }
        if (workPos == null) {
            status = "шукаю " + node.target + " r=" + range;
            if (node.autoPickup && tickScoop(mc, player, node, 4.0)) {
                return;
            }
            MovementKeys.setMove(true, player.horizontalCollision, false, true);
            if (ticksOnNode > 20 * 20) {
                advance(LogicEdge.Port.OUT);
            }
            return;
        }
        BotOverlay.addBreak(workPos);
        if (!BotUtil.canReachBlock(player, workPos)) {
            BotUtil.lookAt(player, net.minecraft.world.phys.Vec3.atCenterOf(workPos));
            MovementKeys.setMove(true, false, false, true);
            return;
        }
        MovementKeys.setMove(false, false, false, false);
        if (BotUtil.mineTick(mc, player, workPos)) {
            workPos = null;
            BotUtil.stopMining(mc);
            if (node.autoPickup) {
                scoopTicks = 40; // ~2s focus on drops
            }
        }
    }

    /** Standalone pickup block: walk onto items in radius (optional target filter). */
    private void runPickup(Minecraft mc, LocalPlayer player, LogicNode node) {
        int need = Math.max(1, node.count);
        if (!node.target.isEmpty() && countTarget(player, node) >= need) {
            advance(LogicEdge.Port.OUT);
            return;
        }
        double range = Math.max(2, Math.min(24, node.radius));
        var drop = nearestMatchingDrop(mc, player, range, node.target);
        if (drop == null) {
            status = "нема дропу r=" + (int) range;
            if (graph.out(node.id, LogicEdge.Port.TRUE).isPresent()
                    || graph.out(node.id, LogicEdge.Port.FALSE).isPresent()) {
                advance(LogicEdge.Port.FALSE);
            } else if (ticksOnNode > 30) {
                advance(LogicEdge.Port.OUT);
            }
            return;
        }
        status = "підбір " + drop.getItem().getHoverName().getString();
        BotUtil.lookAt(player, drop.position().add(0, 0.2, 0));
        double d = player.distanceTo(drop);
        if (d > 0.95) {
            MovementKeys.setMove(true, player.horizontalCollision, false, false);
            return;
        }
        MovementKeys.setMove(false, false, false, false);
        // vanilla pickup when standing on it — wait a moment then continue if more needed
        if (ticksOnNode > 15 && (node.target.isEmpty() || countTarget(player, node) >= need
                || nearestMatchingDrop(mc, player, range, node.target) == null)) {
            if (graph.out(node.id, LogicEdge.Port.TRUE).isPresent()
                    || graph.out(node.id, LogicEdge.Port.FALSE).isPresent()) {
                advance(LogicEdge.Port.TRUE);
            } else {
                advance(LogicEdge.Port.OUT);
            }
        }
    }

    private boolean tickScoop(Minecraft mc, LocalPlayer player, LogicNode node, double range) {
        var drop = nearestMatchingDrop(mc, player, range, node.target.isEmpty() ? "" : relatedDropFilter(node.target));
        // When mining logs, also pick any nearby drop if filter empty-ish
        if (drop == null) {
            drop = BotUtil.nearestItemDrop(mc.level, player, range);
        }
        if (drop == null) {
            if (scoopTicks > 0) {
                scoopTicks--;
            }
            return false;
        }
        BotUtil.lookAt(player, drop.position().add(0, 0.2, 0));
        if (player.distanceTo(drop) > 0.95) {
            MovementKeys.setMove(true, player.horizontalCollision, false, false);
        } else {
            MovementKeys.setMove(false, false, false, false);
        }
        if (scoopTicks > 0) {
            scoopTicks--;
        }
        return true;
    }

    /** Soft filter: logs mining → prefer log/sapling items; else use target as item id. */
    private static String relatedDropFilter(String blockTarget) {
        if (blockTarget == null || blockTarget.isEmpty()) {
            return "";
        }
        String t = blockTarget.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("log") || t.contains("logs")) {
            return "log"; // substring match in nearestMatchingDrop
        }
        return blockTarget;
    }

    private static net.minecraft.world.entity.item.ItemEntity nearestMatchingDrop(
            Minecraft mc, LocalPlayer player, double range, String filter) {
        var any = BotUtil.nearestItemDrop(mc.level, player, range);
        if (any == null) {
            return null;
        }
        if (filter == null || filter.isEmpty()) {
            return any;
        }
        String f = filter.toLowerCase(java.util.Locale.ROOT);
        AABB box = player.getBoundingBox().inflate(range);
        net.minecraft.world.entity.item.ItemEntity best = null;
        double bestD = range * range;
        for (var e : mc.level.getEntities(player, box)) {
            if (!(e instanceof net.minecraft.world.entity.item.ItemEntity item) || !item.isAlive()) {
                continue;
            }
            if (item.getItem().isEmpty()) {
                continue;
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString()
                    .toLowerCase(java.util.Locale.ROOT);
            String name = item.getItem().getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
            boolean ok = id.contains(f.replace("#", "").replace("minecraft:", ""))
                    || name.contains(f)
                    || (f.contains("log") && (id.contains("log") || id.contains("plank") || id.contains("sapling")));
            if (!ok) {
                continue;
            }
            double d = player.distanceToSqr(item);
            if (d < bestD) {
                bestD = d;
                best = item;
            }
        }
        return best;
    }

    private void runCraft(Minecraft mc, LocalPlayer player, LogicNode node) {
        if (countTarget(player, node) >= Math.max(1, node.count)) {
            craft.abort(mc, player);
            advance(LogicEdge.Port.OUT);
            return;
        }
        if (craft.isIdle()) {
            CraftHelper.Recipe r = guessRecipe(node.target);
            if (r == null) {
                chat("§eНема рецепту для " + node.target);
                advance(LogicEdge.Port.OUT);
                return;
            }
            craft.start(r);
            status = "крафт " + r.name().toLowerCase(Locale.ROOT);
        }
        boolean finished = craft.tick(mc, player);
        var walk = craft.getWalkTarget();
        if (walk != null) {
            status = "до верстака…";
            var center = net.minecraft.world.phys.Vec3.atCenterOf(walk);
            if (nav != null) {
                if (!nav.isMoving()) {
                    nav.goTo(center, null);
                }
                nav.tick();
            } else {
                BotUtil.lookAt(player, center);
                MovementKeys.setMove(true, false, false, true);
            }
        } else {
            MovementKeys.clear();
            BotControl.clearLookLock();
        }
        if (countTarget(player, node) >= Math.max(1, node.count)) {
            craft.abort(mc, player);
            advance(LogicEdge.Port.OUT);
            return;
        }
        // One attempt ended without enough items — restart next tick (same node)
        if (finished && craft.isIdle()) {
            // brief pause via ticksOnNode only; will start() again next tick
        }
    }

    private void runPlace(Minecraft mc, LocalPlayer player, LogicNode node) {
        Item item = resolveItem(node.target);
        if (item == null || BotUtil.countItem(player, item) <= 0) {
            status = "нема предмета для PLACE";
            if (ticksOnNode > 40) {
                chat("§eПоставити: немає " + node.target);
                advance(LogicEdge.Port.OUT);
            }
            return;
        }

        if (ticksOnNode <= 1 || workPos == null) {
            workPos = resolvePlaceTarget(mc, player, node);
            placeInvBefore = BotUtil.countItem(player, item);
            placeCooldown = 0;
        }
        if (workPos == null) {
            status = "немає місця для встановлення";
            if (ticksOnNode > 60) {
                chat("§eПоставити: не знайшов місце");
                advance(LogicEdge.Port.OUT);
            }
            return;
        }

        // Already placed (or occupied by same-ish solid)
        if (BotUtil.isFilled(mc.level, workPos)) {
            if (nav != null) {
                nav.stop(false);
            }
            BotOverlay.clearPlace();
            workPos = null;
            placeCooldown = 0;
            placeInvBefore = -1;
            advance(LogicEdge.Port.OUT);
            return;
        }

        BotOverlay.addPlace(workPos);

        if (!BotUtil.isWithinPlaceReach(player, workPos)
                || BotUtil.findPlaceClick(mc.level, player, workPos) == null) {
            status = "йду ставити " + workPos.toShortString();
            var stand = net.minecraft.world.phys.Vec3.atCenterOf(workPos);
            if (nav != null) {
                if (!nav.isMoving()) {
                    nav.goTo(stand, null);
                }
                nav.tick();
            } else {
                BotUtil.lookAt(player, stand);
                MovementKeys.setMove(true, false, false, true);
            }
            if (ticksOnNode > 20 * 25) {
                chat("§eПоставити: не дійшов");
                workPos = null;
                advance(LogicEdge.Port.OUT);
            }
            return;
        }

        MovementKeys.clear();
        if (nav != null) {
            nav.stop(false);
        }

        if (placeCooldown > 0) {
            placeCooldown--;
            // success: item spent or cell filled
            if (BotUtil.isFilled(mc.level, workPos)
                    || (placeInvBefore >= 0 && BotUtil.countItem(player, item) < placeInvBefore)) {
                BotOverlay.clearPlace();
                workPos = null;
                placeCooldown = 0;
                placeInvBefore = -1;
                advance(LogicEdge.Port.OUT);
            }
            return;
        }

        status = "ставлю " + node.shortTarget() + " @ " + workPos.toShortString();
        if (BotUtil.placeItemAt(mc, player, workPos, item)) {
            placeCooldown = 10;
        } else if (ticksOnNode > 20 * 20) {
            chat("§eПоставити: не вийшло");
            BotOverlay.clearPlace();
            workPos = null;
            placeCooldown = 0;
            placeInvBefore = -1;
            advance(LogicEdge.Port.OUT);
        }
    }

    private BlockPos resolvePlaceTarget(Minecraft mc, LocalPlayer player, LogicNode node) {
        if ("coords".equals(node.mode)) {
            BlockPos pos = new BlockPos(node.posX, node.posY, node.posZ);
            if (BotUtil.isFilled(mc.level, pos)) {
                return pos; // treat as done
            }
            if (BotUtil.canOccupyPlacePos(mc, player, pos) || mc.level.getBlockState(pos).canBeReplaced()) {
                return pos;
            }
            // try one block above solid at those XZ
            BlockPos above = pos.above();
            if (BotUtil.canOccupyPlacePos(mc, player, above)) {
                return above;
            }
            return pos;
        }
        int r = Math.max(2, Math.min(24, node.radius <= 0 ? 4 : node.radius));
        return BotUtil.findNearestPlacePos(mc, player, r);
    }

    private void advance(LogicEdge.Port port) {
        ticksOnNode = 0;
        scoopTicks = 0;
        // keep workPos across FIND → GOTO/MINE if next node needs it
        if (port != LogicEdge.Port.TRUE) {
            workPos = null;
        }
        craft.abort(Minecraft.getInstance(), Minecraft.getInstance().player);
        var edge = graph.out(currentId, port);
        if (edge.isEmpty() && port != LogicEdge.Port.OUT) {
            edge = graph.out(currentId, LogicEdge.Port.OUT);
        }
        if (edge.isEmpty()) {
            phase = Phase.DONE;
            chat("§aНоди: кінець гілки");
            stop(false);
            return;
        }
        currentId = edge.get().toId;
    }

    private int countTarget(LocalPlayer player, LogicNode node) {
        String t = node.target == null ? "" : node.target.toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return 0;
        }
        if (t.startsWith("#") || t.contains("logs")) {
            return BotUtil.countMatching(player, s -> s.is(ItemTags.LOGS));
        }
        if (t.contains("planks")) {
            return BotUtil.countMatching(player, s -> s.is(ItemTags.PLANKS));
        }
        Item item = resolveItem(t);
        return item == null ? 0 : BotUtil.countItem(player, item);
    }

    private static boolean matchesBlock(BlockState st, String target) {
        if (target == null || target.isEmpty()) {
            return false;
        }
        String t = target.toLowerCase(Locale.ROOT);
        if (t.contains("log") || t.startsWith("#minecraft:logs")) {
            return BotUtil.isLog(st);
        }
        if (t.contains("cobblestone")) {
            return st.is(net.minecraft.world.level.block.Blocks.COBBLESTONE);
        }
        if (t.equals("minecraft:stone") || t.endsWith(":stone")) {
            return st.is(net.minecraft.world.level.block.Blocks.STONE);
        }
        if (t.contains("dirt")) {
            return st.is(net.minecraft.world.level.block.Blocks.DIRT)
                    || st.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
        }
        if (t.contains("crafting_table")) {
            return st.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
        }
        if (t.contains("furnace")) {
            return st.is(net.minecraft.world.level.block.Blocks.FURNACE);
        }
        if (t.contains("chest")) {
            return st.is(net.minecraft.world.level.block.Blocks.CHEST);
        }
        if (t.contains("iron_ore")) {
            return st.is(net.minecraft.world.level.block.Blocks.IRON_ORE)
                    || st.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_IRON_ORE);
        }
        if (t.contains("coal_ore")) {
            return st.is(net.minecraft.world.level.block.Blocks.COAL_ORE)
                    || st.is(net.minecraft.world.level.block.Blocks.DEEPSLATE_COAL_ORE);
        }
        if (t.contains("sand")) {
            return st.is(net.minecraft.world.level.block.Blocks.SAND);
        }
        if (t.contains("water")) {
            return st.is(net.minecraft.world.level.block.Blocks.WATER);
        }
        try {
            Identifier id = Identifier.parse(t.startsWith("#") ? t.substring(1) : t);
            return BuiltInRegistries.BLOCK.getKey(st.getBlock()).equals(id);
        } catch (Exception e) {
            return false;
        }
    }

    private static Item resolveItem(String target) {
        if (target == null || target.isEmpty()) {
            return null;
        }
        String t = target.toLowerCase(Locale.ROOT);
        if (t.contains("crafting_table")) {
            return Items.CRAFTING_TABLE;
        }
        if (t.contains("furnace")) {
            return Items.FURNACE;
        }
        if (t.contains("stick")) {
            return Items.STICK;
        }
        if (t.contains("oak_planks") || t.equals("minecraft:oak_planks")) {
            return Items.OAK_PLANKS;
        }
        try {
            Identifier id = Identifier.parse(t.startsWith("#") ? "minecraft:oak_log" : t);
            return BuiltInRegistries.ITEM.getValue(id);
        } catch (Exception e) {
            return null;
        }
    }

    private static CraftHelper.Recipe guessRecipe(String target) {
        if (target == null) {
            return null;
        }
        String t = target.toLowerCase(Locale.ROOT);
        if (t.contains("crafting_table")) {
            return CraftHelper.Recipe.CRAFTING_TABLE;
        }
        if (t.contains("furnace")) {
            return CraftHelper.Recipe.FURNACE;
        }
        if (t.contains("wooden_pickaxe")) {
            return CraftHelper.Recipe.WOODEN_PICKAXE;
        }
        if (t.contains("wooden_axe")) {
            return CraftHelper.Recipe.WOODEN_AXE;
        }
        if (t.contains("stone_pickaxe")) {
            return CraftHelper.Recipe.STONE_PICKAXE;
        }
        if (t.contains("stone_sword")) {
            return CraftHelper.Recipe.STONE_SWORD;
        }
        if (t.contains("stone_axe")) {
            return CraftHelper.Recipe.STONE_AXE;
        }
        if (t.contains("iron_pickaxe")) {
            return CraftHelper.Recipe.IRON_PICKAXE;
        }
        if (t.contains("iron_sword")) {
            return CraftHelper.Recipe.IRON_SWORD;
        }
        if (t.contains("iron_axe")) {
            return CraftHelper.Recipe.IRON_AXE;
        }
        if (t.contains("iron_shovel")) {
            return CraftHelper.Recipe.IRON_SHOVEL;
        }
        if (t.contains("boat")) {
            return CraftHelper.Recipe.OAK_BOAT;
        }
        if (t.contains("stick")) {
            return CraftHelper.Recipe.STICKS;
        }
        if (t.contains("plank")) {
            return CraftHelper.Recipe.PLANKS_FROM_LOG;
        }
        return null;
    }

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(msg), false);
        }
    }
}
