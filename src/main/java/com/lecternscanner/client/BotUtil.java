package com.lecternscanner.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Low-level client survival helpers: inv, craft clicks, mine, fight, eat, place.
 */
public final class BotUtil {
    private BotUtil() {
    }

    public static void lookAt(LocalPlayer player, Vec3 target) {
        BotControl.lookAt(player, target, true);
    }

    public static void lookAtBlock(LocalPlayer player, BlockPos pos) {
        BotControl.lookAtBlock(player, pos, true);
    }

    public static void lookAtEntity(LocalPlayer player, Entity e) {
        BotControl.lookAt(player, e.position().add(0, e.getBbHeight() * 0.6, 0), true);
    }

    public static int countItem(LocalPlayer player, Item item) {
        int n = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    public static int countMatching(LocalPlayer player, Predicate<ItemStack> pred) {
        int n = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && pred.test(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    public static int countFoodNutrition(LocalPlayer player) {
        int n = 0;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            FoodProperties food = s.get(DataComponents.FOOD);
            if (food != null) {
                n += food.nutrition() * s.getCount();
            }
        }
        return n;
    }

    public static boolean isFood(ItemStack s) {
        return s.get(DataComponents.FOOD) != null;
    }

    public static int findInvSlot(LocalPlayer player, Predicate<ItemStack> pred) {
        Inventory inv = player.getInventory();
        // prefer hotbar
        for (int i = 0; i < 9; i++) {
            if (!inv.getItem(i).isEmpty() && pred.test(inv.getItem(i))) {
                return i;
            }
        }
        for (int i = 9; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty() && pred.test(inv.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    public static int findInvSlot(LocalPlayer player, Item item) {
        return findInvSlot(player, s -> s.is(item));
    }

    /** Map player inventory index → current container menu slot id. */
    public static int menuSlotForInv(LocalPlayer player, int invIndex) {
        AbstractContainerMenu menu = player.containerMenu;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == invIndex) {
                return i;
            }
        }
        return -1;
    }

    public static void click(Minecraft mc, LocalPlayer player, int menuSlot, int button, ClickType type) {
        if (menuSlot < 0 || mc.gameMode == null) {
            return;
        }
        mc.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, menuSlot, button, type, player);
    }

    /** Survival block interaction reach (vanilla ~4.5 to closest point on AABB). */
    public static final double REACH = 4.5;

    public static boolean canReachBlock(LocalPlayer player, BlockPos pos) {
        return eyeDistToBlock(player, pos) <= REACH;
    }

    /** Distance from eye to closest point on the block cube (not center). */
    public static double eyeDistToBlock(LocalPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        double x = Mth.clamp(eye.x, pos.getX(), pos.getX() + 1.0);
        double y = Mth.clamp(eye.y, pos.getY(), pos.getY() + 1.0);
        double z = Mth.clamp(eye.z, pos.getZ(), pos.getZ() + 1.0);
        return eye.distanceTo(new Vec3(x, y, z));
    }

    /**
     * Put inventory slot into hotbar and select it.
     * {@code invIndex} is player-inventory index (0–8 hotbar, 9+ main).
     */
    public static boolean selectInvSlot(Minecraft mc, LocalPlayer player, int invIndex) {
        if (invIndex < 0 || player == null) {
            return false;
        }
        Inventory inv = player.getInventory();
        if (invIndex < 9) {
            inv.setSelectedSlot(invIndex);
            return true;
        }
        ensurePlayerInventory(mc, player);
        int from = menuSlotForInv(player, invIndex);
        int hotbar = inv.getSelectedSlot();
        if (from < 0) {
            // pick an empty hotbar slot if possible
            for (int h = 0; h < 9; h++) {
                if (inv.getItem(h).isEmpty()) {
                    hotbar = h;
                    break;
                }
            }
            from = menuSlotForInv(player, invIndex);
        }
        if (from < 0) {
            return false;
        }
        inv.setSelectedSlot(hotbar);
        click(mc, player, from, hotbar, ClickType.SWAP);
        return !inv.getItem(hotbar).isEmpty();
    }

    /** Ensure item is in hotbar and selected. Returns true if selected. */
    public static boolean selectItem(Minecraft mc, LocalPlayer player, Predicate<ItemStack> pred) {
        int slot = findInvSlot(player, pred);
        if (slot < 0) {
            return false;
        }
        return selectInvSlot(mc, player, slot);
    }

    public static boolean selectItem(Minecraft mc, LocalPlayer player, Item item) {
        return selectItem(mc, player, s -> s.is(item));
    }

    public static void ensurePlayerInventory(Minecraft mc, LocalPlayer player) {
        if (mc == null || player == null || BotControl.isSettingsMenuOpen(mc)) {
            return;
        }
        // Creative swaps InventoryScreen → CreativeModeInventoryScreen (no survival 2×2 craft)
        if (player.hasInfiniteMaterials()) {
            return;
        }
        if (isPlayerInventoryOpen(mc, player)) {
            return;
        }
        // Close foreign containers first; wait one tick before opening inventory
        if (mc.screen != null) {
            mc.setScreen(null);
            return;
        }
        if (mc.gameMode != null && mc.gameMode.isServerControlledInventory()) {
            player.sendOpenInventory();
        } else {
            mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(player));
        }
    }

    /** True when survival player inventory (2×2 craft) GUI is open. */
    public static boolean isPlayerInventoryOpen(Minecraft mc, LocalPlayer player) {
        return mc != null
                && player != null
                && !player.hasInfiniteMaterials()
                && mc.screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                && player.containerMenu instanceof InventoryMenu;
    }

    /** Any container GUI the bot uses for crafting (inventory / table / furnace…). */
    public static boolean isCraftingGuiOpen(Minecraft mc) {
        return mc != null && mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>;
    }

    public static boolean hasBestTool(LocalPlayer player, Item... preferred) {
        for (Item item : preferred) {
            if (countItem(player, item) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scan full inventory and equip the tool with the highest destroy speed for {@code state}.
     */
    public static boolean selectBestToolFor(Minecraft mc, LocalPlayer player, BlockState state) {
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        float bestScore = -1f;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            float score = toolScore(stack, state);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        // bare hand might be fine for instant-break; still equip best if score > hand
        ItemStack hand = inv.getSelectedItem();
        float handScore = hand.isEmpty() ? 1.0f : toolScore(hand, state);
        if (bestScore <= handScore + 0.001f && bestSlot == inv.getSelectedSlot()) {
            return true;
        }
        if (bestScore <= 1.01f && !isToolItem(inv.getItem(bestSlot))) {
            // nothing better than fist for this block
            return false;
        }
        return selectInvSlot(mc, player, bestSlot);
    }

    private static boolean isToolItem(ItemStack stack) {
        Item i = stack.getItem();
        return i == Items.WOODEN_PICKAXE || i == Items.STONE_PICKAXE || i == Items.IRON_PICKAXE
                || i == Items.GOLDEN_PICKAXE || i == Items.DIAMOND_PICKAXE || i == Items.NETHERITE_PICKAXE
                || i == Items.WOODEN_AXE || i == Items.STONE_AXE || i == Items.IRON_AXE
                || i == Items.GOLDEN_AXE || i == Items.DIAMOND_AXE || i == Items.NETHERITE_AXE
                || i == Items.WOODEN_SHOVEL || i == Items.STONE_SHOVEL || i == Items.IRON_SHOVEL
                || i == Items.GOLDEN_SHOVEL || i == Items.DIAMOND_SHOVEL || i == Items.NETHERITE_SHOVEL
                || i == Items.WOODEN_HOE || i == Items.STONE_HOE || i == Items.IRON_HOE
                || i == Items.GOLDEN_HOE || i == Items.DIAMOND_HOE || i == Items.NETHERITE_HOE
                || i == Items.SHEARS;
    }

    /** Higher = better for breaking this block. */
    public static float toolScore(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        // Prefer correct tool even when speeds are close
        if (stack.isCorrectToolForDrops(state)) {
            speed += 100f;
        }
        // Slight preference for remaining durability
        if (stack.isDamageableItem()) {
            int max = stack.getMaxDamage();
            int dmg = stack.getDamageValue();
            if (max > 0 && dmg >= max - 1) {
                speed *= 0.1f; // almost broken — avoid
            }
        }
        return speed;
    }

    public static boolean selectWeapon(Minecraft mc, LocalPlayer player) {
        Inventory inv = player.getInventory();
        int best = -1;
        float bestDmg = -1f;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            // Prefer swords then axes
            float score = 0f;
            Item it = s.getItem();
            if (it == Items.NETHERITE_SWORD) score = 12;
            else if (it == Items.DIAMOND_SWORD) score = 11;
            else if (it == Items.IRON_SWORD) score = 10;
            else if (it == Items.STONE_SWORD) score = 8;
            else if (it == Items.WOODEN_SWORD || it == Items.GOLDEN_SWORD) score = 6;
            else if (it == Items.NETHERITE_AXE) score = 9;
            else if (it == Items.DIAMOND_AXE) score = 8.5f;
            else if (it == Items.IRON_AXE) score = 7;
            else if (it == Items.STONE_AXE) score = 5;
            else if (it == Items.WOODEN_AXE) score = 3;
            else continue;
            if (score > bestDmg) {
                bestDmg = score;
                best = i;
            }
        }
        return best >= 0 && selectInvSlot(mc, player, best);
    }

    private static BlockPos miningPos;
    private static Direction miningFace;
    private static int mineSwingCooldown;
    private static boolean miningHeld;

    /**
     * Hold-to-mine like vanilla. With {@link BotCheat}: dig toward buried targets through walls.
     * Without cheat: never mines through corners — opens orthogonal neighbor if boxed in.
     */
    public static boolean mineTick(Minecraft mc, LocalPlayer player, BlockPos desired) {
        ClientLevel level = mc.level;
        if (level == null || mc.gameMode == null) {
            return true;
        }

        if (level.getBlockState(desired).isAir()) {
            stopMining(mc);
            return true;
        }

        BlockPos pos = resolveAccessibleMineTarget(level, player, desired);
        // Cheat: prefer first solid on the ray so we tunnel through walls toward buried targets
        if (BotCheat.isEnabled()) {
            BlockPos ray = BotControl.firstBreakableOnRay(mc, player, desired);
            if (ray != null && !level.getBlockState(ray).isAir() && canReachBlock(player, ray)) {
                pos = ray;
            }
        }
        if (!canReachBlock(player, pos)) {
            stopMining(mc);
            return false;
        }

        BlockPos onWay = firstBreakableOrthogonal(level, player, pos);
        if (onWay != null && !level.getBlockState(onWay).isAir()) {
            pos = onWay;
        }
        if (!canReachBlock(player, pos) || level.getBlockState(pos).isAir()) {
            stopMining(mc);
            return level.getBlockState(desired).isAir();
        }

        Direction face = findExposedMineFace(level, player, pos);
        if (face == null && !BotCheat.isEnabled()) {
            BlockPos open = pickCardinalBlockToOpen(level, player, pos);
            if (open != null && !open.equals(pos) && !level.getBlockState(open).isAir()) {
                pos = open;
                face = findExposedMineFace(level, player, pos);
            }
        }
        if (face == null) {
            face = nearestFace(player, pos);
        }

        boolean switched = miningPos == null || !miningPos.equals(pos);
        if (switched) {
            stopMining(mc);
            selectBestToolFor(mc, player, level.getBlockState(pos));
            miningPos = pos.immutable();
            miningFace = face;
            miningHeld = true;
            lookAtBlockFace(player, pos, face);
            BotControl.clearMissTime(mc);
            BotControl.holdAttackKey(mc, true);
            mc.gameMode.startDestroyBlock(pos, miningFace);
            mineSwingCooldown = 0;
        } else {
            lookAtBlockFace(player, pos, miningFace != null ? miningFace : face);
            BotControl.clearMissTime(mc);
            BotControl.holdAttackKey(mc, true);
            if ((player.tickCount & 15) == 0) {
                selectBestToolFor(mc, player, level.getBlockState(pos));
            }
            if (!mc.gameMode.isDestroying()) {
                mc.gameMode.startDestroyBlock(pos, miningFace);
            } else {
                mc.gameMode.continueDestroyBlock(pos, miningFace);
            }
        }

        if (mineSwingCooldown-- <= 0) {
            player.swing(InteractionHand.MAIN_HAND);
            mineSwingCooldown = 4;
        }

        boolean desiredGone = level.getBlockState(desired).isAir();
        boolean currentGone = level.getBlockState(pos).isAir();
        if (currentGone) {
            if (desiredGone) {
                stopMining(mc);
                return true;
            }
            miningPos = null;
            miningHeld = false;
            BotControl.releaseAttackKey(mc);
            if (mc.gameMode.isDestroying()) {
                mc.gameMode.stopDestroyBlock();
            }
            return false;
        }
        return false;
    }

    /**
     * If desired has no exposed face we can use (e.g. solid on all N/E/S/W), dig a
     * cardinal neighbor toward the player first — diagonal approach cannot open it.
     */
    public static BlockPos resolveAccessibleMineTarget(ClientLevel level, LocalPlayer player, BlockPos desired) {
        if (level.getBlockState(desired).isAir()) {
            return desired;
        }
        // Cheat / xray: go for the ore itself; dig path via firstBreakableOrthogonal / ray
        if (BotCheat.isEnabled()) {
            BlockPos onWay = firstBreakableOrthogonal(level, player, desired);
            if (onWay != null && !level.getBlockState(onWay).isAir()) {
                return onWay;
            }
            return desired;
        }
        if (findExposedMineFace(level, player, desired) != null) {
            return desired;
        }
        BlockPos open = pickCardinalBlockToOpen(level, player, desired);
        return open != null ? open : desired;
    }

    /** Exposed face (air on that side), preferring the closest to the player's eyes. */
    public static Direction findExposedMineFace(ClientLevel level, LocalPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        Direction best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            if (!SurvivalAStar.isAirLike(level, pos.relative(face))) {
                continue;
            }
            Vec3 faceCenter = Vec3.atCenterOf(pos).add(
                    face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double d = eye.distanceToSqr(faceCenter);
            if (d < bestDist) {
                bestDist = d;
                best = face;
            }
        }
        return best;
    }

    /** Dig the orthogonal neighbor that opens access; ignores diagonals. */
    public static BlockPos pickCardinalBlockToOpen(ClientLevel level, LocalPlayer player, BlockPos enclosed) {
        double pdx = player.getX() - (enclosed.getX() + 0.5);
        double pdz = player.getZ() - (enclosed.getZ() + 0.5);
        Direction toward = Direction.getApproximateNearest(pdx, 0, pdz);
        if (toward.getAxis().isHorizontal()) {
            BlockPos side = enclosed.relative(toward);
            if (!SurvivalAStar.isAirLike(level, side) && canReachBlock(player, side)) {
                return side;
            }
        }
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = enclosed.relative(d);
            if (SurvivalAStar.isAirLike(level, n) || !canReachBlock(player, n)) {
                continue;
            }
            double dist = player.distanceToSqr(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5);
            if (dist < bestDist) {
                bestDist = dist;
                best = n;
            }
        }
        for (Direction d : new Direction[]{Direction.UP, Direction.DOWN}) {
            BlockPos n = enclosed.relative(d);
            if (SurvivalAStar.isAirLike(level, n) || !canReachBlock(player, n)) {
                continue;
            }
            double dist = player.distanceToSqr(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5);
            if (dist < bestDist) {
                bestDist = dist;
                best = n;
            }
        }
        return best;
    }

    public static void lookAtBlockFace(LocalPlayer player, BlockPos pos, Direction face) {
        Vec3 target = Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.45, face.getStepY() * 0.45, face.getStepZ() * 0.45);
        BotControl.lookAt(player, target, true);
    }

    /** First solid on a Manhattan (cardinal) path toward desired — no diagonal ray. */
    public static BlockPos firstBreakableOrthogonal(ClientLevel level, LocalPlayer player, BlockPos desired) {
        BlockPos feet = player.blockPosition();
        int x = feet.getX();
        int y = Math.max(feet.getY(), Math.min(desired.getY(), feet.getY() + 1));
        int z = feet.getZ();
        int guard = 16;
        while (guard-- > 0 && (x != desired.getX() || z != desired.getZ() || y != desired.getY())) {
            if (x != desired.getX()) {
                x += Integer.signum(desired.getX() - x);
            } else if (z != desired.getZ()) {
                z += Integer.signum(desired.getZ() - z);
            } else {
                y += Integer.signum(desired.getY() - y);
            }
            BlockPos body = new BlockPos(x, feet.getY(), z);
            BlockPos head = body.above();
            BlockPos at = new BlockPos(x, y, z);
            if (!SurvivalAStar.isAirLike(level, at)) {
                return at;
            }
            if (!body.equals(feet) && !SurvivalAStar.isAirLike(level, body)) {
                return body;
            }
            if (!SurvivalAStar.isAirLike(level, head)) {
                return head;
            }
        }
        return desired;
    }

    /** Next cell toward goal using only one axis at a time (no diagonal walk). */
    public static BlockPos cardinalStepToward(BlockPos from, BlockPos to) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        int dy = Integer.signum(to.getY() - from.getY());
        if (dx != 0 && dz != 0) {
            if (Math.abs(to.getX() - from.getX()) >= Math.abs(to.getZ() - from.getZ())) {
                return from.offset(dx, 0, 0);
            }
            return from.offset(0, 0, dz);
        }
        if (dx != 0) {
            return from.offset(dx, 0, 0);
        }
        if (dz != 0) {
            return from.offset(0, 0, dz);
        }
        if (dy != 0) {
            return from.offset(0, dy, 0);
        }
        return to;
    }

    public static void stopMining(Minecraft mc) {
        if (mc != null) {
            BotControl.releaseAttackKey(mc);
            if (mc.gameMode != null && mc.gameMode.isDestroying()) {
                mc.gameMode.stopDestroyBlock();
            }
        }
        miningPos = null;
        miningFace = null;
        mineSwingCooldown = 0;
        miningHeld = false;
    }

    public static BlockPos getMiningPos() {
        return miningPos;
    }

    public static boolean isMining() {
        return miningHeld && miningPos != null;
    }

    public static Direction nearestFace(LocalPlayer player, BlockPos pos) {
        Vec3 eyes = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 d = eyes.subtract(center);
        return Direction.getApproximateNearest(d.x, d.y, d.z);
    }

    public static boolean attackTick(Minecraft mc, LocalPlayer player, LivingEntity target) {
        if (mc.gameMode == null || target == null || !target.isAlive()) {
            return true;
        }
        selectWeapon(mc, player);
        lookAtEntity(player, target);
        if (player.distanceTo(target) > 3.5) {
            return false;
        }
        if (player.getAttackStrengthScale(0.5F) >= 0.9F) {
            mc.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        }
        return !target.isAlive();
    }

    public static LivingEntity nearestHostile(ClientLevel level, LocalPlayer player, double range) {
        AABB box = player.getBoundingBox().inflate(range);
        LivingEntity best = null;
        double bestD = range * range;
        for (Entity e : level.getEntities(player, box)) {
            if (e instanceof LivingEntity le && e instanceof Enemy && le.isAlive() && !le.isRemoved()) {
                double d = player.distanceToSqr(le);
                if (d < bestD) {
                    bestD = d;
                    best = le;
                }
            }
        }
        return best;
    }

    /**
     * Find nearest follow target.
     * @param mode {@code player} or {@code entity}
     * @param filter player name (partial, case-insensitive) / entity id / {@code any|hostile|animal}
     */
    public static Entity findFollowTarget(ClientLevel level, LocalPlayer player, String mode,
                                          String filter, double range) {
        String f = filter == null ? "" : filter.trim().toLowerCase(java.util.Locale.ROOT);
        AABB box = player.getBoundingBox().inflate(range);
        Entity best = null;
        double bestD = range * range;
        boolean wantPlayer = !"entity".equals(mode);

        for (Entity e : level.getEntities(player, box)) {
            if (e == player || !e.isAlive() || e.isRemoved()) {
                continue;
            }
            if (wantPlayer) {
                if (!(e instanceof net.minecraft.world.entity.player.Player)) {
                    continue;
                }
                if (!f.isEmpty()) {
                    String name = e.getName().getString().toLowerCase(java.util.Locale.ROOT);
                    if (!name.contains(f) && !e.getUUID().toString().toLowerCase(java.util.Locale.ROOT).startsWith(f)) {
                        continue;
                    }
                }
            } else {
                if (!(e instanceof LivingEntity)) {
                    continue;
                }
                if (!matchesEntityFilter(e, f)) {
                    continue;
                }
            }
            double d = player.distanceToSqr(e);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static boolean matchesEntityFilter(Entity e, String filter) {
        if (filter.isEmpty() || "any".equals(filter)) {
            return true;
        }
        if ("hostile".equals(filter)) {
            return e instanceof Enemy;
        }
        if ("animal".equals(filter)) {
            String name = e.getType().toShortString();
            return name.contains("cow") || name.contains("pig") || name.contains("sheep")
                    || name.contains("chicken") || name.contains("horse") || name.contains("wolf");
        }
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().toLowerCase(java.util.Locale.ROOT);
        String shortId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        String f = filter.startsWith("minecraft:") ? filter : filter;
        if (f.startsWith("#")) {
            f = f.substring(1);
        }
        return id.equals(f) || shortId.equals(f) || id.contains(f) || shortId.contains(f);
    }

    public static LivingEntity nearestFoodAnimal(ClientLevel level, LocalPlayer player, double range) {
        AABB box = player.getBoundingBox().inflate(range);
        LivingEntity best = null;
        double bestD = range * range;
        for (Entity e : level.getEntities(player, box)) {
            if (!(e instanceof LivingEntity le) || !le.isAlive()) {
                continue;
            }
            String name = e.getType().toShortString();
            if (!(name.contains("cow") || name.contains("pig") || name.contains("sheep") || name.contains("chicken"))) {
                continue;
            }
            double d = player.distanceToSqr(le);
            if (d < bestD) {
                bestD = d;
                best = le;
            }
        }
        return best;
    }

    /** Hold-use food for one tick. Returns true if no longer needs food or no food. */
    public static boolean eatTick(Minecraft mc, LocalPlayer player) {
        if (!player.getFoodData().needsFood() && player.getFoodData().getFoodLevel() >= 16) {
            if (player.isUsingItem()) {
                mc.gameMode.releaseUsingItem(player);
            }
            return true;
        }
        if (!selectItem(mc, player, BotUtil::isFood)) {
            return true; // nothing to eat
        }
        if (!player.isUsingItem()) {
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        return false;
    }

    public static boolean shouldEat(LocalPlayer player) {
        return player.getFoodData().getFoodLevel() <= 14;
    }

    public static boolean placeBlockAgainst(Minecraft mc, LocalPlayer player, BlockPos against, Direction face, Item item) {
        if (!selectItem(mc, player, item) || mc.gameMode == null || mc.level == null) {
            return false;
        }
        BlockPos placePos = against.relative(face);
        return placeItemAt(mc, player, placePos, item);
    }

    /**
     * Place {@code item} so the new block occupies {@code placePos}.
     * Returns true if the use-packet was sent (success may sync a few ticks later on MP).
     */
    public static boolean placeItemAt(Minecraft mc, LocalPlayer player, BlockPos placePos, Item item) {
        if (item == null || !selectItem(mc, player, item) || mc.gameMode == null || mc.level == null) {
            return false;
        }
        if (!canPlaceBlockAt(mc, player, placePos)) {
            return false;
        }
        BlockHitResult hit = findPlaceClick(mc.level, player, placePos);
        if (hit == null) {
            return false;
        }
        lookAt(player, hit.getLocation());
        var result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        return result.consumesAction() || !mc.level.getBlockState(placePos).canBeReplaced();
    }

    /** Vanilla survival/creative block place reach. */
    public static double placeReach(LocalPlayer player) {
        return player.blockInteractionRange();
    }

    /** True if placing a full block here would intersect the player's hitbox. */
    public static boolean overlapsPlayerHitbox(LocalPlayer player, BlockPos placePos) {
        return player.getBoundingBox().intersects(new AABB(placePos));
    }

    /**
     * Safe place cell: empty, outside player hitbox, ≥1 block from feet/head cells,
     * within {@link #placeReach}.
     */
    public static boolean canPlaceBlockAt(Minecraft mc, LocalPlayer player, BlockPos placePos) {
        return canOccupyPlacePos(mc, player, placePos) && isWithinPlaceReach(player, placePos);
    }

    /** Like {@link #canPlaceBlockAt} but ignores reach (for path planning). */
    public static boolean canOccupyPlacePos(Minecraft mc, LocalPlayer player, BlockPos placePos) {
        if (mc.level == null || player == null) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        if (placePos.equals(feet) || placePos.equals(feet.above())) {
            return false;
        }
        if (overlapsPlayerHitbox(player, placePos)) {
            return false;
        }
        BlockState st = mc.level.getBlockState(placePos);
        if (!(st.canBeReplaced() || st.getCollisionShape(mc.level, placePos).isEmpty())) {
            return false;
        }
        return hasSolidNeighbor(mc.level, placePos);
    }

    public static boolean isWithinPlaceReach(LocalPlayer player, BlockPos placePos) {
        return eyeDistToBlock(player, placePos) <= placeReach(player) + 0.35;
    }

    /** True if world already has a non-replaceable block at pos (place succeeded / occupied). */
    public static boolean isFilled(ClientLevel level, BlockPos pos) {
        if (level == null) {
            return false;
        }
        BlockState st = level.getBlockState(pos);
        return !st.canBeReplaced() && !st.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Nearest replaceable cell with a solid neighbor within {@code radius}.
     * Does not require current reach — bot can walk there first.
     */
    public static BlockPos findNearestPlacePos(Minecraft mc, LocalPlayer player, int radius) {
        if (mc.level == null || player == null) {
            return null;
        }
        int r = Math.max(1, Math.min(24, radius));
        BlockPos feet = player.blockPosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (!canOccupyPlacePos(mc, player, pos)) {
                        continue;
                    }
                    double dist = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    double score = dist + Math.abs(dy) * 8.0 + (dy < 0 ? 4.0 : 0);
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    public static boolean hasSolidNeighbor(ClientLevel level, BlockPos placePos) {
        for (Direction face : Direction.values()) {
            BlockPos against = placePos.relative(face);
            if (!level.getBlockState(against).getCollisionShape(level, against).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find a solid neighbor to click so the placed block lands at {@code placePos}.
     * Prefers faces that keep the click within place reach.
     */
    public static BlockHitResult findPlaceClick(ClientLevel level, LocalPlayer player, BlockPos placePos) {
        Vec3 eye = player.getEyePosition();
        BlockHitResult best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            BlockPos against = placePos.relative(face);
            BlockState st = level.getBlockState(against);
            if (st.getCollisionShape(level, against).isEmpty()) {
                continue;
            }
            // Clicking `face.getOpposite()` on `against` places into `placePos`
            Direction clickFace = face.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(against).add(
                    clickFace.getStepX() * 0.5, clickFace.getStepY() * 0.5, clickFace.getStepZ() * 0.5);
            double d = eye.distanceToSqr(hitVec);
            if (eye.distanceTo(hitVec) > placeReach(player) + 0.05) {
                continue;
            }
            if (d < bestDist) {
                bestDist = d;
                best = new BlockHitResult(hitVec, clickFace, against, false);
            }
        }
        return best;
    }

    public static boolean tryPlaceAt(Minecraft mc, LocalPlayer player, BlockPos placePos) {
        Item item = findScaffoldItem(player);
        if (item == null || mc.gameMode == null || mc.level == null) {
            return false;
        }
        if (!canPlaceBlockAt(mc, player, placePos)) {
            return false;
        }
        BlockHitResult hit = findPlaceClick(mc.level, player, placePos);
        if (hit == null) {
            return false;
        }
        selectItem(mc, player, item);
        lookAt(player, hit.getLocation());
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Cobble/dirt/planks/etc. for scaffolding / 2-block climbs. */
    public static boolean hasScaffoldBlock(LocalPlayer player) {
        return findScaffoldItem(player) != null;
    }

    public static Item findScaffoldItem(LocalPlayer player) {
        Item[] prefer = {
                Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.STONE,
                Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
                Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
                Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS,
                Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.DEEPSLATE,
                Items.COBBLED_DEEPSLATE, Items.TUFF, Items.BLACKSTONE
        };
        for (Item item : prefer) {
            if (countItem(player, item) > 0) {
                return item;
            }
        }
        int slot = findInvSlot(player, s -> s.is(ItemTags.DIRT) || s.is(ItemTags.PLANKS)
                || s.is(ItemTags.STONE_CRAFTING_MATERIALS) || s.is(Items.COBBLESTONE));
        return slot >= 0 ? player.getInventory().getItem(slot).getItem() : null;
    }

    public static boolean selectScaffold(Minecraft mc, LocalPlayer player) {
        Item item = findScaffoldItem(player);
        return item != null && selectItem(mc, player, item);
    }

    /** Rough ticks to break a block with the best tool we have (selects tool). */
    public static float estimateBreakTicks(Minecraft mc, LocalPlayer player, ClientLevel level, BlockPos pos) {
        BlockState st = level.getBlockState(pos);
        float hardness = st.getDestroySpeed(level, pos);
        if (hardness < 0) {
            return 1_000_000f;
        }
        if (hardness == 0) {
            return 1f;
        }
        selectBestToolFor(mc, player, st);
        float speed = player.getDestroySpeed(st, pos);
        if (speed <= 0.0001f) {
            return 1_000_000f;
        }
        return Math.max(2f, hardness * 30f / speed);
    }

    /**
     * How many blocks you'd fall if stepping onto {@code stepFeet} (air with no floor).
     * Returns 0 if you can stand there; {@code maxScan}+ if bottomless.
     */
    public static int fallDepthAt(ClientLevel level, BlockPos stepFeet, int maxScan) {
        if (SurvivalAStar.canStandAt(level, stepFeet)) {
            return 0;
        }
        for (int d = 1; d <= maxScan; d++) {
            if (SurvivalAStar.canStandAt(level, stepFeet.below(d))) {
                return d;
            }
        }
        return maxScan + 1;
    }

    /** Fall depth in the horizontal direction the player faces (1 block ahead). */
    public static int fallDepthAhead(ClientLevel level, LocalPlayer player, int maxScan) {
        Direction dir = player.getDirection();
        BlockPos ahead = player.blockPosition().relative(dir);
        return fallDepthAt(level, ahead, maxScan);
    }

    /**
     * Edge-bridge: place under the cell 1 block ahead (never into own hitbox).
     * Target is always within place reach and ≥1 block away horizontally.
     */
    public static boolean tickBridgeForward(Minecraft mc, LocalPlayer player) {
        if (mc.gameMode == null || mc.level == null) {
            return false;
        }
        Direction dir = player.getDirection();
        BlockPos feet = player.blockPosition();
        // 1 block ahead, one below — classic bridge, outside body
        BlockPos placePos = feet.relative(dir).below();
        if (!mc.level.getBlockState(placePos).getCollisionShape(mc.level, placePos).isEmpty()) {
            return true; // already bridged
        }
        if (!canPlaceBlockAt(mc, player, placePos)) {
            // Too close / overlapping — sneak back slightly, don't place into self
            MovementKeys.setMove(false, false, true, false);
            return false;
        }
        float yaw = dir.toYRot();
        BotControl.setLookLock(yaw, 70.0F);
        BotControl.applyLookLock(player);
        MovementKeys.setMove(true, false, true, false);
        return tryPlaceAt(mc, player, placePos);
    }

    /**
     * Climb by placing a block 1+ in front (never under/inside hitbox), then jumping onto it.
     */
    public static boolean tickTowerUp(Minecraft mc, LocalPlayer player) {
        if (mc.gameMode == null || mc.level == null) {
            return false;
        }
        Direction dir = player.getDirection();
        BlockPos feet = player.blockPosition();
        // Prefer stacking in front: front, then front.above(), … within reach
        BlockPos placePos = null;
        for (int up = 0; up <= 2; up++) {
            BlockPos candidate = feet.relative(dir).above(up);
            if (canPlaceBlockAt(mc, player, candidate)) {
                placePos = candidate;
                break;
            }
            // already solid — try one higher
            if (!mc.level.getBlockState(candidate).getCollisionShape(mc.level, candidate).isEmpty()) {
                continue;
            }
        }
        // Fallback: place further ahead (2 blocks) at foot level if front overlaps
        if (placePos == null) {
            for (int dist = 2; dist <= 3; dist++) {
                BlockPos candidate = feet.relative(dir, dist);
                if (canPlaceBlockAt(mc, player, candidate)) {
                    placePos = candidate;
                    break;
                }
            }
        }
        if (placePos == null) {
            MovementKeys.setMove(false, true, false, false);
            return false;
        }
        float yaw = dir.toYRot();
        BotControl.setLookLock(yaw, 55.0F);
        BotControl.applyLookLock(player);
        boolean placed = tryPlaceAt(mc, player, placePos);
        // Jump onto the pillar in front
        MovementKeys.setMove(true, true, false, false);
        return placed;
    }

    public static ItemEntity nearestItemDrop(ClientLevel level, LocalPlayer player, double range) {
        AABB box = player.getBoundingBox().inflate(range);
        ItemEntity best = null;
        double bestD = range * range;
        for (Entity e : level.getEntities(player, box)) {
            if (!(e instanceof ItemEntity item) || !item.isAlive()) {
                continue;
            }
            // still walk toward delayed drops so we're ready when delay ends
            if (item.getItem().isEmpty()) {
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

    public static boolean useOnBlock(Minecraft mc, LocalPlayer player, BlockPos pos) {
        if (mc.gameMode == null) {
            return false;
        }
        lookAtBlock(player, pos);
        Direction face = nearestFace(player, pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public static boolean interactEntity(Minecraft mc, LocalPlayer player, Entity e) {
        if (mc.gameMode == null) {
            return false;
        }
        lookAtEntity(player, e);
        mc.gameMode.interact(player, e, InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    public static BlockPos findNearestBlock(ClientLevel level, BlockPos origin, int range, Predicate<BlockState> pred) {
        return findNearestBlock(level, origin, range, pred, null);
    }

    /**
     * @param area if non-null, only positions inside this AABB are considered
     */
    public static BlockPos findNearestBlock(ClientLevel level, BlockPos origin, int range,
                                            Predicate<BlockState> pred,
                                            net.minecraft.world.phys.AABB area) {
        if (BotCheat.isEnabled()) {
            return findNearestBlockCheat(level, origin, range, pred, area);
        }
        BlockPos best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -Math.min(24, range); dy <= Math.min(24, range); dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (area != null && !area.contains(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)) {
                        continue;
                    }
                    if (!level.isLoaded(p)) {
                        continue;
                    }
                    BlockState st = level.getBlockState(p);
                    if (pred.test(st)) {
                        int d = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (d < bestD) {
                            bestD = d;
                            best = p.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * X-ray scan: all loaded chunks in memory within cheat radius / work area.
     * Does not require line-of-sight or exposed faces.
     */
    public static BlockPos findNearestBlockCheat(ClientLevel level, BlockPos origin, int range,
                                                 Predicate<BlockState> pred,
                                                 net.minecraft.world.phys.AABB area) {
        int r = Math.max(range, BotCheat.scanRadius());
        int minX;
        int minY;
        int minZ;
        int maxX;
        int maxY;
        int maxZ;
        if (area != null) {
            minX = (int) Math.floor(area.minX);
            minY = (int) Math.floor(area.minY);
            minZ = (int) Math.floor(area.minZ);
            maxX = (int) Math.ceil(area.maxX) - 1;
            maxY = (int) Math.ceil(area.maxY) - 1;
            maxZ = (int) Math.ceil(area.maxZ) - 1;
        } else {
            minX = origin.getX() - r;
            minY = Math.max(level.getMinY(), origin.getY() - Math.min(64, r));
            minZ = origin.getZ() - r;
            maxX = origin.getX() + r;
            maxY = Math.min(level.getMinY() + level.getHeight() - 1, origin.getY() + Math.min(64, r));
            maxZ = origin.getZ() + r;
        }

        BlockPos best = null;
        int bestD = Integer.MAX_VALUE;
        int minCx = minX >> 4;
        int maxCx = maxX >> 4;
        int minCz = minZ >> 4;
        int maxCz = maxZ >> 4;

        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    continue;
                }
                var chunk = level.getChunk(cx, cz);
                int x0 = Math.max(minX, cx << 4);
                int x1 = Math.min(maxX, (cx << 4) + 15);
                int z0 = Math.max(minZ, cz << 4);
                int z1 = Math.min(maxZ, (cz << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            BlockPos p = new BlockPos(x, y, z);
                            if (!level.isLoaded(p)) {
                                continue;
                            }
                            BlockState st = chunk.getBlockState(p);
                            if (!pred.test(st)) {
                                continue;
                            }
                            int d = Math.abs(x - origin.getX()) + Math.abs(y - origin.getY()) + Math.abs(z - origin.getZ());
                            if (d < bestD) {
                                bestD = d;
                                best = p.immutable();
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    public static boolean isLog(BlockState st) {
        return st.is(BlockTags.LOGS);
    }

    public static boolean isIronOre(BlockState st) {
        return st.is(BlockTags.IRON_ORES);
    }

    public static boolean isWaterSource(BlockState st) {
        return st.is(Blocks.WATER);
    }

    public static AbstractBoat nearestBoat(ClientLevel level, LocalPlayer player, double range) {
        AABB box = player.getBoundingBox().inflate(range);
        AbstractBoat best = null;
        double bestD = range * range;
        for (Entity e : level.getEntities(player, box)) {
            if (e instanceof AbstractBoat boat) {
                double d = player.distanceToSqr(boat);
                if (d < bestD) {
                    bestD = d;
                    best = boat;
                }
            }
        }
        return best;
    }

    public static boolean hasBoatItem(LocalPlayer player) {
        return countMatching(player, s -> s.is(ItemTags.BOATS)) > 0;
    }

    public static boolean selectBoat(Minecraft mc, LocalPlayer player) {
        return selectItem(mc, player, s -> s.is(ItemTags.BOATS));
    }

    public static Item firstPlankItem(LocalPlayer player) {
        int slot = findInvSlot(player, s -> s.is(ItemTags.PLANKS));
        if (slot < 0) {
            return Items.OAK_PLANKS;
        }
        return player.getInventory().getItem(slot).getItem();
    }

    /** Clear 2x2 craft grid in player inventory menu. */
    public static void clearCraft2x2(Minecraft mc, LocalPlayer player) {
        if (!isPlayerInventoryOpen(mc, player)) {
            ensurePlayerInventory(mc, player);
            return;
        }
        for (int s = 1; s <= 4; s++) {
            click(mc, player, s, 0, ClickType.QUICK_MOVE);
        }
        click(mc, player, 0, 0, ClickType.QUICK_MOVE);
    }

    public static void clearCraft3x3(Minecraft mc, LocalPlayer player) {
        if (!(player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu)) {
            return;
        }
        for (int s = 0; s <= 9; s++) {
            click(mc, player, s, 0, ClickType.QUICK_MOVE);
        }
    }

    /** One craft-grid cell and what to put there. */
    public record CraftNeed(int slot, Predicate<ItemStack> ingredient) {
        public static CraftNeed of(int slot, Item item) {
            return new CraftNeed(slot, s -> s.is(item));
        }

        public static CraftNeed of(int slot, Predicate<ItemStack> ingredient) {
            return new CraftNeed(slot, ingredient);
        }
    }

    /**
     * Unified craft: place one cell per tick → wait for server result → take → verify count increased.
     * Works for survival 2×2 inventory and 3×3 crafting table.
     * stage: 0 clear → 1..n place → n+1 poll → n+2 take → n+3 verify → n+4 done
     */
    public static boolean craftPatternStep(Minecraft mc, LocalPlayer player, Item ingredient, Item resultItem,
                                           int[] craftSlots, int waitTicks, IntRef stage) {
        CraftNeed[] needs = new CraftNeed[craftSlots.length];
        for (int i = 0; i < craftSlots.length; i++) {
            needs[i] = CraftNeed.of(craftSlots[i], ingredient);
        }
        return craftPatternStep(mc, player, needs, p -> countItem(p, resultItem), waitTicks, stage, false);
    }

    public static boolean craftPatternStep(Minecraft mc, LocalPlayer player, Predicate<ItemStack> ingredient,
                                           Item resultItem, int[] craftSlots, int waitTicks, IntRef stage) {
        CraftNeed[] needs = new CraftNeed[craftSlots.length];
        for (int i = 0; i < craftSlots.length; i++) {
            needs[i] = CraftNeed.of(craftSlots[i], ingredient);
        }
        return craftPatternStep(mc, player, needs, p -> countItem(p, resultItem), waitTicks, stage, false);
    }

    public static boolean craftPatternStep(Minecraft mc, LocalPlayer player, CraftNeed[] pattern,
                                           java.util.function.ToIntFunction<LocalPlayer> resultCount,
                                           int waitTicks, IntRef stage, boolean table3x3) {
        if (pattern == null || pattern.length == 0) {
            return false;
        }
        if (table3x3) {
            if (!(player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu)) {
                return false;
            }
        } else if (!isPlayerInventoryOpen(mc, player)) {
            ensurePlayerInventory(mc, player);
            return false;
        }

        int placeCount = pattern.length;
        int stPoll = placeCount + 1;
        int stTake = placeCount + 2;
        int stVerify = placeCount + 3;

        if (stage.wait > 0) {
            stage.wait--;
            return false;
        }
        if (stage.wait < 0) {
            if (!player.containerMenu.getSlot(0).getItem().isEmpty()) {
                stage.wait = 0;
                stage.value = stTake;
            } else {
                stage.wait++;
                if (stage.wait == 0) {
                    stage.value = stVerify;
                }
                return false;
            }
        }

        if (stage.value == 0) {
            if (table3x3) {
                clearCraft3x3(mc, player);
            } else {
                clearCraft2x2(mc, player);
            }
            stage.mark = resultCount.applyAsInt(player);
            stage.value = 1;
            stage.wait = 3;
            return false;
        }

        if (stage.value >= 1 && stage.value <= placeCount) {
            CraftNeed need = pattern[stage.value - 1];
            if (need.slot() >= 0) {
                int inv = findInvSlot(player, need.ingredient());
                if (inv < 0) {
                    stage.value = stVerify;
                    stage.wait = 0;
                    return false;
                }
                int from = menuSlotForInv(player, inv);
                if (from < 0) {
                    return false;
                }
                click(mc, player, from, 0, ClickType.PICKUP);
                click(mc, player, need.slot(), 1, ClickType.PICKUP);
                if (!player.containerMenu.getCarried().isEmpty()) {
                    click(mc, player, from, 0, ClickType.PICKUP);
                }
            }
            stage.value++;
            stage.wait = 3;
            return false;
        }

        if (stage.value == stPoll) {
            if (!player.containerMenu.getSlot(0).getItem().isEmpty()) {
                stage.value = stTake;
                return false;
            }
            stage.wait = -Math.max(waitTicks, 45);
            return false;
        }

        if (stage.value == stTake) {
            if (!player.containerMenu.getSlot(0).getItem().isEmpty()) {
                click(mc, player, 0, 0, ClickType.QUICK_MOVE);
            }
            stage.value = stVerify;
            stage.wait = 4;
            return false;
        }

        if (stage.value >= stVerify) {
            if (table3x3) {
                clearCraft3x3(mc, player);
            } else {
                clearCraft2x2(mc, player);
            }
            boolean ok = resultCount.applyAsInt(player) > stage.mark;
            stage.value = stVerify + 1;
            return ok;
        }

        return false;
    }

    /** Build pattern: same ingredient in every listed slot. */
    public static CraftNeed[] patternSlots(Predicate<ItemStack> ingredient, int... slots) {
        CraftNeed[] needs = new CraftNeed[slots.length];
        for (int i = 0; i < slots.length; i++) {
            needs[i] = CraftNeed.of(slots[i], ingredient);
        }
        return needs;
    }

    public static CraftNeed[] patternSlots(Item ingredient, int... slots) {
        return patternSlots(s -> s.is(ingredient), slots);
    }

    /** Concatenate two patterns (e.g. head material + sticks). */
    public static CraftNeed[] joinPatterns(CraftNeed[] a, CraftNeed[] b) {
        if (a == null || a.length == 0) {
            return b == null ? new CraftNeed[0] : b;
        }
        if (b == null || b.length == 0) {
            return a;
        }
        CraftNeed[] out = new CraftNeed[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Move one item type into furnace ingredient/fuel and take output. */
    public static void furnacePut(Minecraft mc, LocalPlayer player, Item item, int furnaceSlot) {
        int inv = findInvSlot(player, item);
        if (inv < 0) {
            return;
        }
        int from = menuSlotForInv(player, inv);
        click(mc, player, from, 0, ClickType.PICKUP);
        click(mc, player, furnaceSlot, 0, ClickType.PICKUP);
        // return leftover
        click(mc, player, from, 0, ClickType.PICKUP);
    }

    public static void furnaceTakeResult(Minecraft mc, LocalPlayer player) {
        click(mc, player, 2, 0, ClickType.QUICK_MOVE);
    }

    public static List<BlockPos> solidNeighbors(BlockPos pos) {
        List<BlockPos> list = new ArrayList<>(6);
        for (Direction d : Direction.values()) {
            list.add(pos.relative(d));
        }
        return list;
    }

    public static boolean isReplaceableGround(BlockState st) {
        Block b = st.getBlock();
        return st.isAir() || b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.SNOW;
    }

    public static final class IntRef {
        public int value;
        public int wait;
        /** Snapshot of result-item count at start of a craft attempt. */
        public int mark;
    }
}
