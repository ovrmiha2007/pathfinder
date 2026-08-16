package com.lecternscanner.client;

import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * All recipes use one engine: place one cell / tick → wait for server result → take.
 */
public final class CraftHelper {
    public enum Recipe {
        PLANKS_FROM_LOG,
        STICKS,
        CRAFTING_TABLE,
        FURNACE,
        WOODEN_PICKAXE,
        WOODEN_AXE,
        STONE_PICKAXE,
        STONE_SWORD,
        STONE_AXE,
        IRON_PICKAXE,
        IRON_SWORD,
        IRON_AXE,
        IRON_SHOVEL,
        OAK_BOAT
    }

    private static final Predicate<ItemStack> LOGS = s -> s.is(ItemTags.LOGS);
    private static final Predicate<ItemStack> PLANKS = s -> s.is(ItemTags.PLANKS);

    private Recipe current;
    private final BotUtil.IntRef stage = new BotUtil.IntRef();
    private int openWait;
    private int guiSettle;
    private int stuckTicks;
    private net.minecraft.core.BlockPos walkTarget;
    private int recipeAttempts;
    private boolean warnedCreative;

    public void reset() {
        current = null;
        stage.value = 0;
        stage.wait = 0;
        stage.mark = 0;
        openWait = 0;
        guiSettle = 0;
        stuckTicks = 0;
        walkTarget = null;
        recipeAttempts = 0;
        warnedCreative = false;
    }

    public boolean isIdle() {
        return current == null;
    }

    public void start(Recipe recipe) {
        reset();
        current = recipe;
    }

    public Recipe getCurrent() {
        return current;
    }

    public net.minecraft.core.BlockPos getWalkTarget() {
        return walkTarget;
    }

    public void abort(Minecraft mc, LocalPlayer player) {
        BotControl.closeBotScreens(mc);
        reset();
    }

    /** @return true when this attempt finished (success or abandoned). */
    public boolean tick(Minecraft mc, LocalPlayer player) {
        if (current == null) {
            return true;
        }
        boolean done = switch (current) {
            case PLANKS_FROM_LOG -> run2x2(mc, player,
                    BotUtil.patternSlots(LOGS, 1),
                    p -> BotUtil.countMatching(p, PLANKS));
            case STICKS -> run2x2(mc, player,
                    BotUtil.patternSlots(PLANKS, 1, 3),
                    p -> BotUtil.countItem(p, Items.STICK));
            case CRAFTING_TABLE -> run2x2(mc, player,
                    BotUtil.patternSlots(PLANKS, 1, 2, 3, 4),
                    p -> BotUtil.countItem(p, Items.CRAFTING_TABLE));
            case FURNACE -> run3x3(mc, player,
                    BotUtil.patternSlots(Items.COBBLESTONE, 1, 2, 3, 4, 6, 7, 8, 9),
                    p -> BotUtil.countItem(p, Items.FURNACE));
            case WOODEN_PICKAXE -> run3x3(mc, player,
                    headAndStick(PLANKS, Items.STICK, new int[]{1, 2, 3}, new int[]{5, 8}),
                    p -> BotUtil.countItem(p, Items.WOODEN_PICKAXE));
            case WOODEN_AXE -> run3x3(mc, player,
                    headAndStick(PLANKS, Items.STICK, new int[]{1, 2, 4}, new int[]{5, 8}),
                    p -> BotUtil.countItem(p, Items.WOODEN_AXE));
            case STONE_PICKAXE -> run3x3(mc, player,
                    headAndStick(Items.COBBLESTONE, Items.STICK, new int[]{1, 2, 3}, new int[]{5, 8}),
                    p -> BotUtil.countItem(p, Items.STONE_PICKAXE));
            case STONE_SWORD -> run3x3(mc, player,
                    headAndStick(Items.COBBLESTONE, Items.STICK, new int[]{2, 5}, new int[]{8}),
                    p -> BotUtil.countItem(p, Items.STONE_SWORD));
            case STONE_AXE -> run3x3(mc, player,
                    headAndStick(Items.COBBLESTONE, Items.STICK, new int[]{1, 2, 4}, new int[]{5, 8}),
                    p -> BotUtil.countItem(p, Items.STONE_AXE));
            case IRON_PICKAXE -> run3x3(mc, player,
                    headAndStick(Items.IRON_INGOT, Items.STICK, new int[]{1, 2, 3}, new int[]{5, 8}),
                    p -> BotUtil.countItem(p, Items.IRON_PICKAXE));
            case IRON_SWORD -> run3x3(mc, player,
                    headAndStick(Items.IRON_INGOT, Items.STICK, new int[]{2, 5}, new int[]{8}),
                    p -> BotUtil.countItem(p, Items.IRON_SWORD));
            case IRON_AXE -> run3x3(mc, player,
                    headAndStick(Items.IRON_INGOT, Items.STICK, new int[]{1, 2, 4}, new int[]{5, 8}),
                    p -> BotUtil.countItem(p, Items.IRON_AXE));
            case IRON_SHOVEL -> run3x3(mc, player,
                    headAndStick(Items.IRON_INGOT, Items.STICK, new int[]{2}, new int[]{5, 8}),
                    p -> BotUtil.countItem(p, Items.IRON_SHOVEL));
            case OAK_BOAT -> run3x3(mc, player,
                    BotUtil.patternSlots(PLANKS, 4, 6, 7, 8, 9),
                    p -> BotUtil.countMatching(p, s -> s.is(ItemTags.BOATS)));
        };
        if (walkTarget != null || openWait > 0 || (guiSettle > 0 && guiSettle < 8)
                || BotUtil.isPlayerInventoryOpen(mc, player)
                || player.containerMenu instanceof CraftingMenu) {
            stuckTicks = 0;
        } else {
            stuckTicks++;
            if (stuckTicks > 20 * 20) {
                abort(mc, player);
                return true;
            }
        }
        return done;
    }

    private static BotUtil.CraftNeed[] headAndStick(Predicate<ItemStack> head, Item stick,
                                                    int[] headSlots, int[] stickSlots) {
        return BotUtil.joinPatterns(
                BotUtil.patternSlots(head, headSlots),
                BotUtil.patternSlots(stick, stickSlots));
    }

    private static BotUtil.CraftNeed[] headAndStick(Item head, Item stick, int[] headSlots, int[] stickSlots) {
        return headAndStick(s -> s.is(head), stick, headSlots, stickSlots);
    }

    private boolean run2x2(Minecraft mc, LocalPlayer player, BotUtil.CraftNeed[] pattern,
                           ToIntFunction<LocalPlayer> resultCount) {
        if (!preparePlayerCraftGui(mc, player)) {
            return false;
        }
        if (!canFillPattern(player, pattern) && stage.value == 0) {
            return false;
        }
        return finishAttempt(mc, player,
                BotUtil.craftPatternStep(mc, player, pattern, resultCount, 45, stage, false),
                pattern.length);
    }

    private boolean run3x3(Minecraft mc, LocalPlayer player, BotUtil.CraftNeed[] pattern,
                           ToIntFunction<LocalPlayer> resultCount) {
        if (!ensureCraftingTable(mc, player)) {
            return false;
        }
        if (!(player.containerMenu instanceof CraftingMenu)) {
            return false;
        }
        if (guiSettle < 6) {
            guiSettle++;
            return false;
        }
        if (!canFillPattern(player, pattern) && stage.value == 0) {
            return false;
        }
        return finishAttempt(mc, player,
                BotUtil.craftPatternStep(mc, player, pattern, resultCount, 50, stage, true),
                pattern.length);
    }

    /** Simulate taking one item per pattern cell from inventory + craft grid. */
    private static boolean canFillPattern(LocalPlayer player, BotUtil.CraftNeed[] pattern) {
        java.util.HashMap<Item, Integer> counts = new java.util.HashMap<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                counts.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        var menu = player.containerMenu;
        int gridEnd = menu instanceof CraftingMenu ? 9 : 4;
        for (int s = 1; s <= gridEnd; s++) {
            ItemStack stack = menu.getSlot(s).getItem();
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        for (BotUtil.CraftNeed need : pattern) {
            boolean ok = false;
            for (var e : counts.entrySet()) {
                if (e.getValue() <= 0) {
                    continue;
                }
                if (need.ingredient().test(new ItemStack(e.getKey()))) {
                    e.setValue(e.getValue() - 1);
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private boolean finishAttempt(Minecraft mc, LocalPlayer player, boolean success, int patternLen) {
        int doneStage = patternLen + 3;
        if (success) {
            finish(mc, player);
            return true;
        }
        if (stage.value > doneStage) {
            recipeAttempts++;
            if (recipeAttempts >= 8) {
                finish(mc, player);
                return true;
            }
            stage.value = 0;
            stage.wait = 4;
            stage.mark = 0;
        }
        return false;
    }

    private boolean preparePlayerCraftGui(Minecraft mc, LocalPlayer player) {
        if (player.hasInfiniteMaterials()) {
            if (!warnedCreative) {
                warnedCreative = true;
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§cКрафт 2×2 потрібен Survival (у Creative інвентар без верстака не крафтить)"),
                        false);
            }
            return false;
        }
        BotUtil.ensurePlayerInventory(mc, player);
        if (!BotUtil.isPlayerInventoryOpen(mc, player)) {
            guiSettle = 0;
            return false;
        }
        if (guiSettle < 8) {
            guiSettle++;
            return false;
        }
        return true;
    }

    private boolean ensureCraftingTable(Minecraft mc, LocalPlayer player) {
        walkTarget = null;
        if (player.containerMenu instanceof CraftingMenu) {
            return true;
        }
        guiSettle = 0;
        if (openWait > 0) {
            openWait--;
            return false;
        }
        var level = mc.level;
        if (level == null) {
            return false;
        }
        var pos = BotUtil.findNearestBlock(level, player.blockPosition(), 24,
                st -> st.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE));
        if (pos == null) {
            if (BotUtil.countItem(player, Items.CRAFTING_TABLE) <= 0) {
                return false;
            }
            var spot = BotUtil.findNearestPlacePos(mc, player, 4);
            if (spot == null) {
                return false;
            }
            BotUtil.placeItemAt(mc, player, spot, Items.CRAFTING_TABLE);
            openWait = 8;
            return false;
        }
        double distSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (distSq > 12) {
            walkTarget = pos;
            return false;
        }
        BotUtil.useOnBlock(mc, player, pos);
        openWait = 10;
        return false;
    }

    private void finish(Minecraft mc, LocalPlayer player) {
        BotControl.closeBotScreens(mc);
        reset();
    }
}
