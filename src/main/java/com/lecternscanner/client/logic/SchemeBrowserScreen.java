package com.lecternscanner.client.logic;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Save / load / delete named logic schemes.
 */
public final class SchemeBrowserScreen extends Screen {
    private static final int BG = 0xFF0E141B;
    private static final int PANEL = 0xFF161E28;
    private static final int PANEL_EDGE = 0xFF2A3544;
    private static final int MUTED = 0xFF8A9AAB;
    private static final int TEXT = 0xFFE8EEF4;
    private static final int ACCENT = 0xFF3DB8A0;
    private static final int WARN = 0xFFE8C07A;
    private static final int ROW_H = 22;

    private final Screen parent;
    private final LogicGraph working;
    private EditBox nameBox;
    private List<String> schemes = new ArrayList<>();
    private String selected = "";
    private int scroll;
    private String toast = "";
    private int toastTicks;

    public SchemeBrowserScreen(Screen parent, LogicGraph working) {
        super(Component.literal("Схеми Pathfinder"));
        this.parent = parent;
        this.working = working;
    }

    @Override
    protected void init() {
        refreshList();
        int cx = this.width / 2;
        int top = 28;

        nameBox = new EditBox(this.font, cx - 160, top, 200, 18, Component.literal("name"));
        nameBox.setMaxLength(48);
        nameBox.setHint(Component.literal("назва схеми"));
        String active = LogicGraphStore.activeScheme();
        if (!active.isEmpty()) {
            nameBox.setValue(active);
            selected = active;
        } else if (working.name != null && !working.name.isBlank() && !"default".equals(working.name)) {
            nameBox.setValue(working.name);
        }
        this.addRenderableWidget(nameBox);

        this.addRenderableWidget(Button.builder(Component.literal("Зберегти як"), b -> doSaveAs())
                .bounds(cx + 48, top, 88, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("Завантажити"), b -> doLoad())
                .bounds(cx - 160, this.height - 28, 90, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Видалити"), b -> doDelete())
                .bounds(cx - 62, this.height - 28, 80, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Оновити"), b -> {
            refreshList();
            toast("Список оновлено", 30);
        }).bounds(cx + 26, this.height - 28, 70, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("← Назад"), b -> goBack())
                .bounds(cx + 104, this.height - 28, 70, 18).build());
    }

    private void refreshList() {
        schemes = LogicGraphStore.listSchemes();
        scroll = Mth.clamp(scroll, 0, Math.max(0, schemes.size() * ROW_H - listHeight()));
    }

    private int listTop() {
        return 58;
    }

    private int listBottom() {
        return this.height - 40;
    }

    private int listHeight() {
        return Math.max(40, listBottom() - listTop());
    }

    private void toast(String msg, int ticks) {
        toast = msg;
        toastTicks = ticks;
    }

    private void doSaveAs() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            toast("Вкажи назву схеми", 40);
            return;
        }
        LogicGraphStore.setCurrent(working);
        if (LogicGraphStore.schemeExists(name)) {
            // overwrite
        }
        if (LogicGraphStore.saveScheme(name)) {
            selected = LogicGraphStore.sanitizeName(name);
            nameBox.setValue(selected);
            refreshList();
            toast("Збережено: " + selected, 50);
        } else {
            toast("Не вдалося зберегти", 40);
        }
    }

    private void doLoad() {
        String name = !selected.isEmpty() ? selected : nameBox.getValue().trim();
        if (name.isEmpty()) {
            toast("Обери схему зі списку", 40);
            return;
        }
        if (!LogicGraphStore.loadScheme(name)) {
            toast("Нема файлу: " + name, 40);
            return;
        }
        toast("Завантажено: " + LogicGraphStore.activeScheme(), 40);
        if (this.minecraft != null) {
            this.minecraft.setScreen(new LogicEditorScreen(LogicGraphStore.current()));
        }
    }

    private void doDelete() {
        String name = !selected.isEmpty() ? selected : nameBox.getValue().trim();
        if (name.isEmpty()) {
            toast("Обери схему для видалення", 40);
            return;
        }
        if (LogicGraphStore.deleteScheme(name)) {
            if (selected.equals(LogicGraphStore.sanitizeName(name))) {
                selected = "";
            }
            refreshList();
            toast("Видалено: " + name, 40);
        } else {
            toast("Не вдалося видалити", 40);
        }
    }

    private void goBack() {
        LogicGraphStore.setCurrent(working);
        if (this.minecraft != null) {
            this.minecraft.setScreen(new LogicEditorScreen(working));
        }
    }

    @Override
    public void tick() {
        if (toastTicks > 0) {
            toastTicks--;
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, BG);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, "Схеми Pathfinder", cx, 10, ACCENT);
        g.drawString(this.font, "Зберегти поточний граф під назвою або завантажити зі списку",
                cx - 160, 42, MUTED, false);

        int lx = cx - 160;
        int lw = 320;
        int ly = listTop();
        int lb = listBottom();
        g.fill(lx - 4, ly - 4, lx + lw + 4, lb + 4, PANEL_EDGE);
        g.fill(lx - 3, ly - 3, lx + lw + 3, lb + 3, PANEL);

        g.enableScissor(lx, ly, lx + lw, lb);
        int y = ly - scroll;
        if (schemes.isEmpty()) {
            g.drawString(this.font, "Поки немає збережених схем", lx + 8, ly + 10, MUTED, false);
        } else {
            for (String name : schemes) {
                if (y + ROW_H >= ly && y <= lb) {
                    boolean on = name.equals(selected);
                    boolean hover = mouseX >= lx && mouseX < lx + lw && mouseY >= y && mouseY < y + ROW_H
                            && mouseY >= ly && mouseY < lb;
                    if (on) {
                        g.fill(lx, y, lx + lw, y + ROW_H, 0xFF2A6F6F);
                    } else if (hover) {
                        g.fill(lx, y, lx + lw, y + ROW_H, 0xFF243040);
                    }
                    String mark = name.equals(LogicGraphStore.activeScheme()) ? " ★" : "";
                    g.drawString(this.font, name + mark, lx + 8, y + 7, on ? ACCENT : TEXT, false);
                }
                y += ROW_H;
            }
        }
        g.disableScissor();

        String active = LogicGraphStore.activeScheme();
        g.drawString(this.font,
                active.isEmpty() ? "Активна: (без назви)" : "Активна: " + active,
                cx - 160, this.height - 44, MUTED, false);
        if (toastTicks > 0) {
            g.drawCenteredString(this.font, toast, cx, this.height - 56, WARN);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != 0) {
            return false;
        }
        int cx = this.width / 2;
        int lx = cx - 160;
        int lw = 320;
        int ly = listTop();
        int lb = listBottom();
        double mx = event.x();
        double my = event.y();
        if (mx < lx || mx >= lx + lw || my < ly || my >= lb) {
            return false;
        }
        int idx = (int) ((my - ly + scroll) / ROW_H);
        if (idx >= 0 && idx < schemes.size()) {
            selected = schemes.get(idx);
            nameBox.setValue(selected);
            if (doubleClick) {
                doLoad();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, schemes.size() * ROW_H - listHeight());
        scroll = Mth.clamp(scroll - (int) (scrollY * 14), 0, max);
        return true;
    }

    @Override
    public void onClose() {
        goBack();
    }
}
