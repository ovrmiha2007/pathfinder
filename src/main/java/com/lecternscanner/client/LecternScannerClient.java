package com.lecternscanner.client;

import com.lecternscanner.LecternScannerMod;
import com.lecternscanner.client.logic.LogicEditorScreen;
import com.lecternscanner.client.logic.LogicExecutor;
import com.lecternscanner.client.logic.LogicGraphStore;
import com.mojang.brigadier.arguments.BoolArgumentType;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.glfw.GLFW;

@Mod(value = LecternScannerMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = LecternScannerMod.MODID, value = Dist.CLIENT)
public class LecternScannerClient {
    public static final PathNavigator NAV = new PathNavigator();
    public static final LogicExecutor LOGIC = new LogicExecutor();

    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(LecternScannerMod.MODID, "main"));

    public static KeyMapping KEY_MENU;
    public static KeyMapping KEY_STOP;

    public LecternScannerClient(IEventBus modEventBus) {
        LecternScannerMod.LOGGER.info("Pathfinder: press [ for editor | by ovrmiha");
        modEventBus.addListener(LecternScannerClient::registerKeys);
        // Do NOT load graphs here — Minecraft.getInstance() is still null during mod construction
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        KEY_MENU = new KeyMapping("key.pathfinder.menu", GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);
        KEY_STOP = new KeyMapping("key.pathfinder.stop", GLFW.GLFW_KEY_J, CATEGORY);
        event.register(KEY_MENU);
        event.register(KEY_STOP);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        var root = Commands.literal("ls")
                .then(Commands.literal("logic")
                        .executes(ctx -> {
                            Minecraft.getInstance().setScreen(new LogicEditorScreen());
                            return 1;
                        })
                        .then(Commands.literal("run").executes(ctx -> {
                            LogicGraphStore.load();
                            LOGIC.start(LogicGraphStore.current(), NAV);
                            ctx.getSource().sendSuccess(() ->
                                    Component.literal("Логіку запущено: " + LogicGraphStore.current().name), false);
                            return 1;
                        })))
                .then(Commands.literal("stop").executes(ctx -> {
                    stopAllActivity(true);
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "logic=" + LOGIC.status()
                                    + " | nav=" + NAV.getState() + "/" + NAV.getMode()
                                    + " | " + NAV.getLastAnalysis()), false);
                    return 1;
                }))
                .then(Commands.literal("overlay")
                        .executes(ctx -> {
                            BotOverlay.setEnabled(!BotOverlay.isEnabled());
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "overlay = " + BotOverlay.isEnabled()), false);
                            return 1;
                        })
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> {
                                    boolean v = BoolArgumentType.getBool(ctx, "value");
                                    BotOverlay.setEnabled(v);
                                    ctx.getSource().sendSuccess(() ->
                                            Component.literal("overlay = " + v), false);
                                    return 1;
                                })))
                .then(Commands.literal("nav")
                        .then(Commands.literal("auto").executes(ctx -> {
                            NAV.setMode(PathNavigator.Mode.AUTO);
                            ctx.getSource().sendSuccess(() -> Component.literal("nav = AUTO"), false);
                            return 1;
                        }))
                        .then(Commands.literal("walk").executes(ctx -> {
                            NAV.setMode(PathNavigator.Mode.FORCE_WALK);
                            ctx.getSource().sendSuccess(() -> Component.literal("nav = WALK"), false);
                            return 1;
                        }))
                        .then(Commands.literal("fly").executes(ctx -> {
                            NAV.setMode(PathNavigator.Mode.FORCE_FLY);
                            ctx.getSource().sendSuccess(() -> Component.literal("nav = FLY"), false);
                            return 1;
                        })));

        var ls = event.getDispatcher().register(root);
        event.getDispatcher().register(Commands.literal("lecternscan").redirect(ls));
        event.getDispatcher().register(Commands.literal("pathfinder").redirect(ls));
        event.getDispatcher().register(Commands.literal("pf").redirect(ls));
    }

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (BotControl.isSettingsMenuOpen(mc)) {
            MovementKeys.clear();
            BotControl.clearLookLock();
            BotUtil.stopMining(mc);
            return;
        }
        // Don't fight the mouse/keys while crafting GUI is open
        if (BotUtil.isCraftingGuiOpen(mc)) {
            MovementKeys.clear();
            BotControl.clearLookLock();
            BotUtil.stopMining(mc);
            return;
        }
        if (NAV.isMoving() || LOGIC.isActive() || MovementKeys.isActive()) {
            MovementKeys.tickApply();
            BotControl.applyLookLock(mc.player);
            BotControl.suppressMouseLook(mc);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        BotControl.tickPending();

        if (KEY_MENU != null && KEY_MENU.consumeClick()) {
            if (mc.screen instanceof LogicEditorScreen || mc.screen instanceof LecternScannerMenuScreen) {
                mc.setScreen(null);
            } else {
                stopAllActivity(true);
                mc.setScreen(new LogicEditorScreen());
            }
        }
        if (KEY_STOP != null && KEY_STOP.consumeClick()) {
            stopAllActivity(true);
        }

        if (BotControl.isSettingsMenuOpen(mc)) {
            return;
        }

        LOGIC.tick();
        if (!LOGIC.isActive()) {
            NAV.tick();
        }

        if (BotUtil.isCraftingGuiOpen(mc)) {
            MovementKeys.clear();
            BotControl.clearLookLock();
            return;
        }

        if (NAV.isMoving() || LOGIC.isActive() || BotUtil.isMining() || MovementKeys.isActive()) {
            BotControl.applyLookLock(mc.player);
            MovementKeys.tickApply();
        } else {
            BotControl.clearLookLock();
        }
    }

    public static void stopAllActivity(boolean announce) {
        Minecraft mc = Minecraft.getInstance();
        LOGIC.stop(false);
        NAV.stop(false);
        BotUtil.stopMining(mc);
        BotControl.clearLookLock();
        BotControl.forceCleanInput(mc.player);
        MovementKeys.clear();
        if (announce && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§eБот зупинено"), false);
        }
    }

    @SubscribeEvent
    public static void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || BotControl.isSettingsMenuOpen(mc) || BotUtil.isCraftingGuiOpen(mc)) {
            return;
        }
        if (NAV.isMoving() || LOGIC.isActive() || BotUtil.isMining()) {
            BotControl.suppressMouseLook(mc);
            BotControl.applyLookLock(mc.player);
        }
    }
}
