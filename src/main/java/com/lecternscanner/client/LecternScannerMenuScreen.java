package com.lecternscanner.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Small hub: open logic editor / stop / overlay. Opened from logic «← Меню».
 */
public final class LecternScannerMenuScreen extends Screen {
    private static final int PANEL_W = 280;
    private static final int PANEL_H = 168;

    private static final int COL_BG = 0xF0161E28;
    private static final int COL_EDGE = 0xFF2A3544;
    private static final int COL_ACCENT = 0xFF3DB8A0;
    private static final int COL_TEXT = 0xFFE8EEF4;
    private static final int COL_MUTED = 0xFF8A9AAB;

    public LecternScannerMenuScreen() {
        super(Component.literal("Pathfinder"));
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int x = left + 16;
        int y = top + 40;
        int w = PANEL_W - 32;

        this.addRenderableWidget(Button.builder(Component.literal("Логіка нод ▦"), b -> {
            this.minecraft.setScreen(new com.lecternscanner.client.logic.LogicEditorScreen());
        }).bounds(x, y, w, 22).build());
        y += 28;

        this.addRenderableWidget(Button.builder(Component.literal("■  Стоп"), b -> {
            LecternScannerClient.stopAllActivity(true);
        }).bounds(x, y, w, 22).build());
        y += 28;

        this.addRenderableWidget(CycleButton.onOffBuilder(BotOverlay.isEnabled())
                .create(x, y, w, 20, Component.literal("Оверлей шляху"),
                        (btn, val) -> BotOverlay.setEnabled(val)));
        y += 26;

        this.addRenderableWidget(CycleButton.builder(this::navModeLabel, LecternScannerClient.NAV.getMode())
                .withValues(PathNavigator.Mode.AUTO, PathNavigator.Mode.FORCE_WALK, PathNavigator.Mode.FORCE_FLY)
                .create(x, y, w, 20, Component.literal("Навігація"),
                        (btn, mode) -> LecternScannerClient.NAV.setMode(mode)));
    }

    private Component navModeLabel(PathNavigator.Mode mode) {
        return Component.literal(switch (mode) {
            case FORCE_WALK -> "Пішки";
            case FORCE_FLY -> "Політ";
            default -> "Авто";
        });
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x99000000);
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        g.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, COL_EDGE);
        g.fill(left, top, left + PANEL_W, top + PANEL_H, COL_BG);
        g.drawCenteredString(this.font, "PATHFINDER  v" + ModVersion.VERSION, this.width / 2, top + 12, COL_ACCENT);
        g.drawCenteredString(this.font, "by ovrmiha  ·  [ редактор · J стоп ]", this.width / 2, top + PANEL_H - 16, COL_MUTED);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        String st = LecternScannerClient.LOGIC.isActive()
                ? LecternScannerClient.LOGIC.status()
                : (LecternScannerClient.NAV.isMoving() ? "nav…" : "idle");
        g.drawCenteredString(this.font, st, this.width / 2, (this.height - PANEL_H) / 2 + 28, COL_TEXT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
