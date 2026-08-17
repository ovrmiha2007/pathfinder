package com.lecternscanner.client.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.lecternscanner.client.BotControl;
import com.lecternscanner.client.LecternScannerClient;
import com.lecternscanner.client.ModVersion;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Visual logic-block editor: infinite canvas, orthogonal wires, zoom, undo.
 */
public final class LogicEditorScreen extends Screen {
    private static final int NODE_W = 128;
    private static final int NODE_H = 52;
    private static final int PALETTE_W = 148;
    private static final int INSPECT_W = 196;
    private static final int MAX_UNDO = 48;
    private static final float ZOOM_MIN = 0.2f;
    private static final float ZOOM_MAX = 4.0f;
    private static final int GRID_WORLD = 24;
    private static final int NODE_GAP = 8;

    private static final int BG = 0xFF0E141B;
    private static final int PANEL = 0xFF161E28;
    private static final int PANEL_EDGE = 0xFF2A3544;
    private static final int MUTED = 0xFF8A9AAB;
    private static final int TEXT = 0xFFE8EEF4;
    private static final int ACCENT = 0xFF3DB8A0;
    private static final int WARN = 0xFFE8C07A;

    private LogicGraph graph;
    private LogicNode dragging;
    private float dragOffX;
    private float dragOffY;
    private boolean dragPushedUndo;
    private LogicNode linkFrom;
    private LogicEdge.Port linkPort = LogicEdge.Port.OUT;
    private LogicNode selected;
    private LogicEdge selectedEdge;
    private int selectedWaypointIndex = -1;
    private boolean draggingWaypoint;
    private NodeKind pendingPlace;
    private EditBox targetBox;
    private String toast = "";
    private int toastTicks;
    private int scrollPalette;
    private int scrollInspect;

    /** World→screen: screen = pan + world * zoom */
    private float zoom = 1.0f;
    private float panX;
    private float panY;
    private boolean panning;
    private boolean spaceHeld;
    private boolean canvasPanCandidate;
    private boolean canvasPanning;
    private double panLastMx;
    private double panLastMy;
    private double panStartMx;
    private double panStartMy;

    private final Deque<LogicGraph> undoStack = new ArrayDeque<>();

    public LogicEditorScreen() {
        this(null);
    }

    /** {@code reuse} keeps an in-memory graph (e.g. return from scheme browser). */
    public LogicEditorScreen(LogicGraph reuse) {
        super(Component.literal("Редактор нод"));
        if (reuse != null) {
            LogicGraphStore.setCurrent(reuse);
            this.graph = reuse;
        } else {
            LogicGraphStore.load();
            this.graph = LogicGraphStore.current();
        }
        migrateOldScreenCoords();
        repairAllWires();
    }

    private void migrateOldScreenCoords() {
        boolean underPalette = false;
        for (LogicNode n : graph.nodes) {
            if (n.x >= 0 && n.x < PALETTE_W) {
                underPalette = true;
                break;
            }
        }
        if (!underPalette) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (LogicNode n : graph.nodes) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
        }
        int dx = 40 - minX;
        int dy = 40 - minY;
        for (LogicNode n : graph.nodes) {
            n.x += dx;
            n.y += dy;
        }
    }

    private int canvasLeft() {
        return PALETTE_W;
    }

    private int canvasRight() {
        return this.width - INSPECT_W;
    }

    private boolean onCanvas(double mx, double my) {
        return mx >= canvasLeft() && mx < canvasRight() && my >= 56 && my < this.height;
    }

    private float toScreenX(float worldX) {
        return panX + worldX * zoom;
    }

    private float toScreenY(float worldY) {
        return panY + worldY * zoom;
    }

    private float toWorldX(double screenX) {
        return (float) ((screenX - panX) / zoom);
    }

    private float toWorldY(double screenY) {
        return (float) ((screenY - panY) / zoom);
    }

    private int nodeScreenW() {
        return Math.max(8, Math.round(NODE_W * zoom));
    }

    private int nodeScreenH() {
        return Math.max(8, Math.round(NODE_H * zoom));
    }

    private void resetView() {
        panX = canvasLeft() + 24;
        panY = 72;
        zoom = 1.0f;
    }

    private void pushUndo() {
        undoStack.addLast(graph.copy());
        while (undoStack.size() > MAX_UNDO) {
            undoStack.removeFirst();
        }
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            toast("Нема що скасувати", 30);
            return;
        }
        graph = undoStack.removeLast();
        clearSelection();
        dragging = null;
        draggingWaypoint = false;
        rebuildInspectorWidgets();
        toast("Скасовано (Ctrl+Z)", 30);
    }

    private void clearSelection() {
        selected = null;
        selectedEdge = null;
        selectedWaypointIndex = -1;
        linkFrom = null;
        pendingPlace = null;
    }

    @Override
    protected void init() {
        resetView();
        int y = 6;
        int x = PALETTE_W + 8;
        this.addRenderableWidget(Button.builder(Component.literal("▶ Запуск"), b -> {
            LogicGraphStore.setCurrent(graph);
            LogicGraphStore.save();
            this.onClose();
            BotControl.queueAfterMenu(() -> LecternScannerClient.LOGIC.start(graph, LecternScannerClient.NAV));
        }).bounds(x, y, 70, 18).build());
        x += 74;
        this.addRenderableWidget(Button.builder(Component.literal("Зберегти"), b -> {
            LogicGraphStore.setCurrent(graph);
            LogicGraphStore.save();
            String active = LogicGraphStore.activeScheme();
            if (!active.isEmpty()) {
                LogicGraphStore.saveScheme(active);
                toast("Збережено: " + active, 40);
            } else {
                toast("Збережено (робоча копія)", 40);
            }
        }).bounds(x, y, 64, 18).build());
        x += 68;
        this.addRenderableWidget(Button.builder(Component.literal("Схеми…"), b -> {
            LogicGraphStore.setCurrent(graph);
            LogicGraphStore.save();
            this.minecraft.setScreen(new SchemeBrowserScreen(this, graph));
        }).bounds(x, y, 58, 18).build());
        x += 62;
        this.addRenderableWidget(Button.builder(Component.literal("Очистити"), b -> {
            pushUndo();
            graph = LogicGraph.blank();
            LogicGraphStore.setCurrent(graph);
            LogicGraphStore.setActiveScheme("");
            clearSelection();
            toast("Новий граф", 40);
        }).bounds(x, y, 64, 18).build());
        x += 68;
        this.addRenderableWidget(Button.builder(Component.literal("−"), b -> setZoom(zoom / 1.15f)).bounds(x, y, 18, 18).build());
        x += 20;
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> setZoom(zoom * 1.15f)).bounds(x, y, 18, 18).build());
        x += 20;
        this.addRenderableWidget(Button.builder(Component.literal("1:1"), b -> {
            resetView();
            toast("Масштаб 100%", 25);
        }).bounds(x, y, 28, 18).build());

        this.addRenderableWidget(Button.builder(Component.literal("← Меню"), b -> {
            LogicGraphStore.setCurrent(graph);
            LogicGraphStore.save();
            this.minecraft.setScreen(new com.lecternscanner.client.LecternScannerMenuScreen());
        }).bounds(canvasRight() - 72, y, 64, 18).build());

        targetBox = new EditBox(this.font, canvasRight() + 10, this.height - 26, INSPECT_W - 20, 16,
                Component.literal("target"));
        targetBox.setMaxLength(64);
        targetBox.setHint(Component.literal("id або #tag"));
        targetBox.setResponder(s -> {
            if (selected != null) {
                selected.target = s;
            }
        });
        this.addRenderableWidget(targetBox);
        rebuildInspectorWidgets();
    }

    private void setZoom(float next) {
        float cx = (canvasLeft() + canvasRight()) / 2f;
        float cy = this.height / 2f;
        float wx = toWorldX(cx);
        float wy = toWorldY(cy);
        zoom = Mth.clamp(next, ZOOM_MIN, ZOOM_MAX);
        panX = cx - wx * zoom;
        panY = cy - wy * zoom;
        toast("Масштаб " + Math.round(zoom * 100) + "%", 20);
    }

    private void zoomAtCursor(double mouseX, double mouseY, float factor) {
        float wx = toWorldX(mouseX);
        float wy = toWorldY(mouseY);
        zoom = Mth.clamp(zoom * factor, ZOOM_MIN, ZOOM_MAX);
        panX = (float) mouseX - wx * zoom;
        panY = (float) mouseY - wy * zoom;
        toast("Масштаб " + Math.round(zoom * 100) + "%", 15);
    }

    private void rebuildInspectorWidgets() {
        if (targetBox != null) {
            targetBox.setX(canvasRight() + 10);
            targetBox.setY(this.height - 26);
            targetBox.setWidth(INSPECT_W - 20);
            if (selected != null && selected.kind != NodeKind.START && selected.kind != NodeKind.END
                    && selected.kind != NodeKind.AREA && selected.kind != NodeKind.CHEAT) {
                targetBox.setValue(selected.target == null ? "" : selected.target);
                targetBox.setEditable(true);
                targetBox.visible = true;
            } else {
                targetBox.setValue("");
                targetBox.setEditable(false);
                targetBox.visible = false;
            }
        }
    }

    private void toast(String msg, int ticks) {
        this.toast = msg;
        this.toastTicks = ticks;
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
        g.fill(0, 0, this.width, 34, 0xFF121A22);

        g.enableScissor(canvasLeft(), 56, canvasRight(), this.height);
        drawGrid(g);
        g.disableScissor();

        g.fill(0, 0, PALETTE_W, this.height, PANEL);
        g.fill(PALETTE_W - 1, 0, PALETTE_W, this.height, PANEL_EDGE);
        g.drawString(this.font, "НОДИ", 12, 10, ACCENT, false);
        g.drawString(this.font, "клік → потім полотно", 12, 22, MUTED, false);
        String ver = "v" + ModVersion.VERSION + (ModVersion.isBeta() ? " beta" : "");
        g.drawString(this.font, ver, 12, this.height - 14, MUTED, false);
        drawPalette(g, mouseX, mouseY);

        g.fill(canvasRight(), 0, this.width, this.height, PANEL);
        g.fill(canvasRight(), 0, canvasRight() + 1, this.height, PANEL_EDGE);
        g.drawString(this.font, "ВЛАСТИВОСТІ", canvasRight() + 12, 10, ACCENT, false);
        drawInspector(g, mouseX, mouseY);

        g.drawString(this.font,
                "ПКМ порт=зв'язок · Del · Ctrl+Z · колесо=масштаб · СКМ/порожнє ЛКМ=пан · Alt+клік лінія=точка згину · тягни квадратики",
                PALETTE_W + 8, 30, MUTED, false);
        g.drawString(this.font, Math.round(zoom * 100) + "%", canvasRight() - 100, 10, MUTED, false);
        String scheme = LogicGraphStore.activeScheme();
        g.drawString(this.font,
                scheme.isEmpty() ? "схема: —" : "схема: " + scheme,
                canvasRight() - 100, 22, MUTED, false);
        if (toastTicks > 0) {
            g.drawString(this.font, toast, PALETTE_W + 8, 42, ACCENT, false);
        }
        if (pendingPlace != null) {
            g.drawString(this.font, "Постав «" + pendingPlace.label + "» кліком на полотні (Esc — скасувати)",
                    PALETTE_W + 8, 54, WARN, false);
        } else if (linkFrom != null) {
            g.drawString(this.font, "Зв'язок: " + linkFrom.kind.label + " [" + portLabel(linkPort) + "] → клікніть ціль",
                    PALETTE_W + 8, 54, WARN, false);
        } else if (selectedEdge != null) {
            g.drawString(this.font, "Зв'язок — Del · Alt+клік/2×клік=згин · тягни квадратики · «Обхід» у панелі",
                    PALETTE_W + 8, 54, WARN, false);
        }
    }

    private void drawGrid(GuiGraphics g) {
        int x0 = canvasLeft();
        int x1 = canvasRight();
        int y0 = 56;
        int y1 = this.height;

        int step = GRID_WORLD;
        while (step * zoom < 8) {
            step *= 2;
        }

        float worldLeft = toWorldX(x0);
        float worldTop = toWorldY(y0);
        float worldRight = toWorldX(x1);
        float worldBottom = toWorldY(y1);

        int startX = (int) (Math.floor(worldLeft / step) * step);
        int startY = (int) (Math.floor(worldTop / step) * step);

        for (int wx = startX; wx <= worldRight + step; wx += step) {
            int sx = Math.round(toScreenX(wx));
            if (sx >= x0 && sx < x1) {
                g.fill(sx, y0, sx + 1, y1, 0x18FFFFFF);
            }
        }
        for (int wy = startY; wy <= worldBottom + step; wy += step) {
            int sy = Math.round(toScreenY(wy));
            if (sy >= y0 && sy < y1) {
                g.fill(x0, sy, x1, sy + 1, 0x18FFFFFF);
            }
        }
    }

    private void drawPalette(GuiGraphics g, int mouseX, int mouseY) {
        int py = 36 - scrollPalette;
        NodeKind.Category last = null;
        for (NodeKind kind : NodeKind.values()) {
            if (kind.category != last) {
                last = kind.category;
                String cat = switch (kind.category) {
                    case FLOW -> "ПОТІК";
                    case COND -> "УМОВИ";
                    case ACTION -> "ДІЇ";
                };
                if (py > 30 && py < this.height - 28) {
                    g.drawString(this.font, cat, 12, py, MUTED, false);
                }
                py += 14;
            }
            if (py > 30 && py < this.height - 28) {
                boolean hover = mouseX >= 8 && mouseX < PALETTE_W - 8 && mouseY >= py && mouseY < py + 20;
                boolean pending = pendingPlace == kind;
                int bg = pending ? brighten(kind.color, 40) : (hover ? brighten(kind.color, 20) : kind.color);
                fillRoundish(g, 8, py, PALETTE_W - 8, py + 20, bg);
                if (pending) {
                    g.fill(8, py, PALETTE_W - 8, py + 1, ACCENT);
                    g.fill(8, py + 19, PALETTE_W - 8, py + 20, ACCENT);
                }
                g.drawString(this.font, kind.label, 16, py + 6, TEXT, false);
            }
            py += 24;
        }
    }

    private void drawInspector(GuiGraphics g, int mouseX, int mouseY) {
        int ix = canvasRight() + 10;
        int iy = 28 - scrollInspect;

        if (selectedEdge != null) {
            LogicNode from = graph.find(selectedEdge.fromId).orElse(null);
            LogicNode to = graph.find(selectedEdge.toId).orElse(null);
            String fromLabel = from != null ? from.kind.label : "?";
            String toLabel = to != null ? to.kind.label : "?";
            g.drawString(this.font, "Зв'язок", ix, iy, TEXT, false);
            iy += 14;
            g.drawString(this.font, fromLabel + " → " + toLabel, ix, iy, MUTED, false);
            iy += 14;
            g.drawString(this.font, "Порт: " + portLabel(selectedEdge.port), ix, iy, MUTED, false);
            iy += 14;
            g.drawString(this.font, "Згинів: " + selectedEdge.waypoints.size(), ix, iy, MUTED, false);
            iy += 18;
            boolean hover = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 22;
            fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 22, hover ? 0xFF2A6F6F : 0xFF1A2430);
            g.drawCenteredString(this.font, "Обхід", ix + (INSPECT_W - 20) / 2, iy + 7, ACCENT);
            iy += 28;
            g.drawWordWrap(this.font,
                    Component.literal("Alt+клік або подвійний клік на лінії — нова точка згину. Тягни квадратики для зміни маршруту."),
                    ix, iy, INSPECT_W - 20, MUTED);
            return;
        }

        if (selected == null) {
            g.drawWordWrap(this.font, Component.literal("Обери ноду на полотні — тут з'являться умови, радіус і пресети."),
                    ix, iy, INSPECT_W - 20, MUTED);
            return;
        }

        g.drawString(this.font, selected.kind.label, ix, iy, TEXT, false);
        iy += 14;
        if (selected.kind != NodeKind.AREA && selected.kind != NodeKind.START
                && selected.kind != NodeKind.END && selected.kind != NodeKind.CHEAT) {
            g.drawString(this.font, selected.shortTarget().isEmpty() ? "без цілі" : selected.shortTarget(),
                    ix, iy, MUTED, false);
            iy += 18;
        } else {
            iy += 4;
        }

        if (selected.kind == NodeKind.CHEAT) {
            g.drawWordWrap(this.font, Component.literal(
                            "Увімкнути чит для наступних нод: усі чанки в клієнтській пам'яті, "
                                    + "цілі крізь стіни, шлях з урахуванням цього."),
                    ix, iy, INSPECT_W - 20, MUTED);
            iy += 48;
            iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Радіус", selected.radius);
            return;
        }

        if (selected.kind == NodeKind.AREA) {
            g.drawString(this.font, "Тип зони", ix, iy, MUTED, false);
            iy += 12;
            for (TargetPresets.Preset p : TargetPresets.AREA_MODES) {
                boolean on = p.id().equals(selected.mode);
                boolean hover = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 18;
                fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 18, on ? 0xFF2A6F6F : (hover ? 0xFF243040 : 0xFF1A2430));
                g.drawString(this.font, p.label(), ix + 6, iy + 5, on ? ACCENT : TEXT, false);
                iy += 22;
            }
            iy += 4;
            if ("box".equals(selected.mode)) {
                g.drawString(this.font, "Кут A (від)", ix, iy, MUTED, false);
                iy += 12;
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "X₁", selected.posX);
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Y₁", selected.posY);
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Z₁", selected.posZ);
                boolean h1 = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 18;
                fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 18, h1 ? 0xFF2A6F6F : 0xFF1A2430);
                g.drawCenteredString(this.font, "Взяти XYZ₁", ix + (INSPECT_W - 20) / 2, iy + 5, ACCENT);
                iy += 26;
                g.drawString(this.font, "Кут B (до)", ix, iy, MUTED, false);
                iy += 12;
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "X₂", selected.posX2);
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Y₂", selected.posY2);
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Z₂", selected.posZ2);
                boolean h2 = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 18;
                fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 18, h2 ? 0xFF2A6F6F : 0xFF1A2430);
                g.drawCenteredString(this.font, "Взяти XYZ₂", ix + (INSPECT_W - 20) / 2, iy + 5, ACCENT);
            } else {
                g.drawString(this.font, "Центр", ix, iy, MUTED, false);
                iy += 12;
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "X", selected.posX);
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Y", selected.posY);
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Z", selected.posZ);
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Радіус", selected.radius);
                boolean hover = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 18;
                fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 18, hover ? 0xFF2A6F6F : 0xFF1A2430);
                g.drawCenteredString(this.font, "Взяти мої XYZ", ix + (INSPECT_W - 20) / 2, iy + 5, ACCENT);
            }
            return;
        }

        if (selected.kind != NodeKind.START && selected.kind != NodeKind.END) {
            iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Кількість", selected.count);
            if (needsRadius(selected.kind) || (selected.kind == NodeKind.IF && "has_near".equals(selected.mode))
                    || (selected.kind == NodeKind.PLACE && !"coords".equals(selected.mode))) {
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Радіус", selected.radius);
            }
            if (selected.kind == NodeKind.MINE) {
                iy = drawToggle(g, ix, iy, mouseX, mouseY, "Підбирати дроп", selected.autoPickup);
            }
            iy += 6;
        }

        if (selected.kind == NodeKind.PLACE) {
            g.drawString(this.font, "Куди ставити", ix, iy, MUTED, false);
            iy += 12;
            for (TargetPresets.Preset p : TargetPresets.PLACE_MODES) {
                boolean on = p.id().equals(selected.mode);
                boolean hover = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 18;
                fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 18, on ? 0xFF2A6F6F : (hover ? 0xFF243040 : 0xFF1A2430));
                g.drawString(this.font, p.label(), ix + 6, iy + 5, on ? ACCENT : TEXT, false);
                iy += 22;
            }
            if ("coords".equals(selected.mode)) {
                iy += 2;
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "X", selected.posX);
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Y", selected.posY);
                iy = drawStatRow(g, ix, iy, mouseX, mouseY, "Z", selected.posZ);
                boolean hover = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 18;
                fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 18, hover ? 0xFF2A6F6F : 0xFF1A2430);
                g.drawCenteredString(this.font, "Взяти мої XYZ", ix + (INSPECT_W - 20) / 2, iy + 5, ACCENT);
                iy += 22;
            }
            iy += 4;
            g.drawString(this.font, "Що ставити", ix, iy, MUTED, false);
            iy += 12;
            for (TargetPresets.Preset p : TargetPresets.forKind(selected.kind)) {
                iy = drawChip(g, ix, iy, mouseX, mouseY, p, selected.target.equals(p.id()));
            }
        } else if (selected.kind == NodeKind.IF) {
            g.drawString(this.font, "Тип умови", ix, iy, MUTED, false);
            iy += 12;
            for (TargetPresets.Preset p : TargetPresets.IF_MODES) {
                boolean on = p.id().equals(selected.mode);
                boolean hover = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 18;
                fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 18, on ? 0xFF2A6F6F : (hover ? 0xFF243040 : 0xFF1A2430));
                g.drawString(this.font, p.label(), ix + 6, iy + 5, on ? ACCENT : TEXT, false);
                iy += 22;
            }
            iy += 4;
            g.drawString(this.font, "Ціль умови", ix, iy, MUTED, false);
            iy += 12;
            for (TargetPresets.Preset p : TargetPresets.targetsForIfMode(selected.mode)) {
                iy = drawChip(g, ix, iy, mouseX, mouseY, p, selected.target.equals(p.id()));
            }
        } else if (selected.kind != NodeKind.END) {
            List<TargetPresets.Preset> presets = TargetPresets.forKind(selected.kind);
            if (!presets.isEmpty()) {
                g.drawString(this.font, "Швидкий вибір", ix, iy, MUTED, false);
                iy += 12;
                for (TargetPresets.Preset p : presets) {
                    iy = drawChip(g, ix, iy, mouseX, mouseY, p, selected.target.equals(p.id()));
                }
            }
        }

        iy += 8;
        if (selected.kind != NodeKind.END && selected.kind != NodeKind.START
                && selected.kind != NodeKind.AREA && selected.kind != NodeKind.CHEAT) {
            g.drawString(this.font, "Свій id:", ix, iy, MUTED, false);
        }
    }

    private int drawChip(GuiGraphics g, int ix, int iy, int mouseX, int mouseY, TargetPresets.Preset p, boolean on) {
        boolean hover = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 16;
        fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 16, on ? 0xFF3D5A80 : (hover ? 0xFF243040 : 0xFF1A2430));
        g.drawString(this.font, p.label(), ix + 6, iy + 4, on ? 0xFFB8E0D8 : TEXT, false);
        return iy + 20;
    }

    private int drawStatRow(GuiGraphics g, int ix, int iy, int mouseX, int mouseY, String label, int value) {
        return drawStatRow(g, ix, iy, mouseX, mouseY, label, value, false);
    }

    private int drawStatRow(GuiGraphics g, int ix, int iy, int mouseX, int mouseY, String label, int value, boolean wide) {
        String text = label + ": " + value;
        g.drawString(this.font, text, ix, iy + 3, TEXT, false);
        int bx = ix + INSPECT_W - 62;
        boolean hMinus = hitBox(mouseX, mouseY, bx, iy, 20, 16);
        boolean hPlus = hitBox(mouseX, mouseY, bx + 24, iy, 20, 16);
        fillRoundish(g, bx, iy, bx + 20, iy + 16, hMinus ? 0xFF3A4555 : 0xFF243040);
        fillRoundish(g, bx + 24, iy, bx + 44, iy + 16, hPlus ? 0xFF3A4555 : 0xFF243040);
        g.drawCenteredString(this.font, "−", bx + 10, iy + 4, TEXT);
        g.drawCenteredString(this.font, "+", bx + 34, iy + 4, TEXT);
        return iy + 22;
    }

    private static boolean needsRadius(NodeKind kind) {
        return kind == NodeKind.FIND_BLOCK
                || kind == NodeKind.HAS_NEAR
                || kind == NodeKind.IN_RADIUS
                || kind == NodeKind.MINE
                || kind == NodeKind.PICKUP
                || kind == NodeKind.GOTO;
    }

    private int drawToggle(GuiGraphics g, int ix, int iy, int mouseX, int mouseY, String label, boolean on) {
        boolean hover = mouseX >= ix && mouseX < ix + INSPECT_W - 20 && mouseY >= iy && mouseY < iy + 18;
        fillRoundish(g, ix, iy, ix + INSPECT_W - 20, iy + 18, hover ? 0xFF243040 : 0xFF1A2430);
        drawCheckSprite(g, ix + 4, iy + 3, 12, on);
        g.drawString(this.font, label, ix + 22, iy + 5, on ? ACCENT : TEXT, false);
        return iy + 22;
    }

    /** Pixel checkmark — no font glyphs (✓/↑ break in MC font). */
    private static void drawCheckSprite(GuiGraphics g, int x, int y, int size, boolean on) {
        g.fill(x, y, x + size, y + size, 0xFF0A0E12);
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, on ? 0xFF2A6F6F : 0xFF243040);
        if (!on) {
            return;
        }
        // thick ✓ from fills (short arm + long arm)
        int s = Math.max(8, size);
        // short rising stroke
        g.fill(x + 2, y + s / 2, x + 4, y + s / 2 + 2, 0xFFE8EEF4);
        g.fill(x + 3, y + s / 2 + 1, x + 5, y + s / 2 + 3, 0xFFE8EEF4);
        g.fill(x + 4, y + s / 2 + 2, x + 6, y + s / 2 + 4, 0xFFE8EEF4);
        // long falling stroke
        g.fill(x + 5, y + s / 2 + 2, x + 7, y + s / 2 + 4, 0xFFE8EEF4);
        g.fill(x + 6, y + s / 2, x + 8, y + s / 2 + 2, 0xFFE8EEF4);
        g.fill(x + 7, y + s / 2 - 2, x + 9, y + s / 2, 0xFFE8EEF4);
        g.fill(x + 8, y + s / 2 - 4, x + 10, y + s / 2 - 2, 0xFFE8EEF4);
    }

    private static boolean hitBox(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String portLabel(LogicEdge.Port p) {
        return switch (p) {
            case TRUE -> "ТАК";
            case FALSE -> "НІ";
            case MAYBE -> "?";
            default -> "ДАЛІ";
        };
    }

    private static int brighten(int argb, int add) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, ((argb >>> 16) & 0xFF) + add);
        int gr = Math.min(255, ((argb >>> 8) & 0xFF) + add);
        int b = Math.min(255, (argb & 0xFF) + add);
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }

    private static void fillRoundish(GuiGraphics g, int x1, int y1, int x2, int y2, int col) {
        g.fill(x1 + 1, y1, x2 - 1, y2, col);
        g.fill(x1, y1 + 1, x2, y2 - 1, col);
    }

    /** World-space wire endpoints [x1,y1,x2,y2] or null. */
    private int[] edgeEndpoints(LogicEdge e) {
        LogicNode a = graph.find(e.fromId).orElse(null);
        LogicNode b = graph.find(e.toId).orElse(null);
        if (a == null || b == null) {
            return null;
        }
        int[] out = LogicGraph.portAnchor(a, e.port, true);
        int[] in = LogicGraph.portAnchor(b, e.port, false);
        return new int[]{out[0], out[1], in[0], in[1]};
    }

    private List<WireRouter.Seg> edgeSegmentsWorld(LogicEdge e) {
        int[] ep = edgeEndpoints(e);
        if (ep == null) {
            return List.of();
        }
        return WireRouter.poly(ep[0], ep[1], e.waypoints, ep[2], ep[3]);
    }

    private void orthogonalizeEdge(LogicEdge e) {
        int[] ep = edgeEndpoints(e);
        if (ep == null) {
            return;
        }
        List<LogicEdge.Point> ortho = WireRouter.orthogonalize(ep[0], ep[1], e.waypoints, ep[2], ep[3]);
        e.waypoints.clear();
        e.waypoints.addAll(ortho);
    }

    private void rerouteEdgeAround(LogicEdge e) {
        int[] ep = edgeEndpoints(e);
        if (ep == null) {
            return;
        }
        List<int[]> obstacles = new ArrayList<>();
        for (LogicNode n : graph.nodes) {
            if (n.id.equals(e.fromId) || n.id.equals(e.toId)) {
                continue;
            }
            obstacles.add(new int[]{n.x, n.y, NODE_W, NODE_H});
        }
        e.waypoints.clear();
        e.waypoints.addAll(WireRouter.routeAround(ep[0], ep[1], ep[2], ep[3], obstacles, 8));
    }

    /** Fix legacy/broken edges that were drawn as stubs (diagonal without bends). */
    private void repairAllWires() {
        for (LogicEdge e : graph.edges) {
            int[] ep = edgeEndpoints(e);
            if (ep == null) {
                continue;
            }
            boolean needs = false;
            if (ep[0] != ep[2] && ep[1] != ep[3] && e.waypoints.isEmpty()) {
                needs = true;
            } else {
                for (WireRouter.Seg s : WireRouter.poly(ep[0], ep[1], e.waypoints, ep[2], ep[3])) {
                    // after poly() split, should be H/V; if still diagonal, repair
                    if (s.x1() != s.x2() && s.y1() != s.y2()) {
                        needs = true;
                        break;
                    }
                }
            }
            if (needs) {
                rerouteEdgeAround(e);
            }
        }
    }

    private void rerouteEdgesTouching(LogicNode node) {
        for (LogicEdge e : graph.edges) {
            if (e.fromId.equals(node.id) || e.toId.equals(node.id)) {
                rerouteEdgeAround(e);
            }
        }
    }

    private void separateFromOthers(LogicNode moving) {
        for (LogicNode other : graph.nodes) {
            if (other != moving) {
                WireRouter.separate(moving, other, NODE_W, NODE_H, NODE_GAP);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        g.enableScissor(canvasLeft(), 56, canvasRight(), this.height);

        for (LogicEdge e : graph.edges) {
            int col = e == selectedEdge ? 0xFFFFD27A : switch (e.port) {
                case TRUE -> 0xFF6BCB8A;
                case FALSE -> 0xFFE07A7A;
                case MAYBE -> 0xFFE8C07A;
                default -> 0xFF7A8C9E;
            };
            drawEdgeWire(g, e, col, e == selectedEdge ? 3 : 2);
        }

        if (selectedEdge != null) {
            drawWaypointHandles(g, selectedEdge);
        }

        if (linkFrom != null) {
            int[] out = LogicGraph.portAnchor(linkFrom, linkPort, true);
            int sx = Math.round(toScreenX(out[0]));
            int sy = Math.round(toScreenY(out[1]));
            drawPreviewWire(g, sx, sy, mouseX, mouseY, 0xAAE8C07A, 2);
        }

        if (pendingPlace != null && onCanvas(mouseX, mouseY)) {
            float wx = toWorldX(mouseX) - NODE_W / 2f;
            float wy = toWorldY(mouseY) - NODE_H / 2f;
            int sx = Math.round(toScreenX(wx));
            int sy = Math.round(toScreenY(wy));
            g.fill(sx, sy, sx + nodeScreenW(), sy + nodeScreenH(), (pendingPlace.color & 0x00FFFFFF) | 0x55000000);
        }

        for (LogicNode n : graph.nodes) {
            drawNode(g, n, selected == n);
        }

        g.disableScissor();
    }

    private void drawEdgeWire(GuiGraphics g, LogicEdge e, int col, int thick) {
        for (WireRouter.Seg s : edgeSegmentsWorld(e)) {
            int x1 = Math.round(toScreenX(s.x1()));
            int y1 = Math.round(toScreenY(s.y1()));
            int x2 = Math.round(toScreenX(s.x2()));
            int y2 = Math.round(toScreenY(s.y2()));
            drawThickSegment(g, x1, y1, x2, y2, col, thick);
        }
    }

    private void drawPreviewWire(GuiGraphics g, int x1, int y1, int x2, int y2, int col, int thick) {
        int mx = (x1 + x2) / 2;
        drawThickSegment(g, x1, y1, mx, y1, col, thick);
        drawThickSegment(g, mx, y1, mx, y2, col, thick);
        drawThickSegment(g, mx, y2, x2, y2, col, thick);
    }

    private static void drawThickSegment(GuiGraphics g, int x1, int y1, int x2, int y2, int col, int thick) {
        int t = Math.max(1, thick);
        if (x1 == x2 && y1 == y2) {
            g.fill(x1 - t, y1 - t, x1 + t + 1, y1 + t + 1, col);
            return;
        }
        // Axis-aligned
        if (y1 == y2) {
            g.fill(Math.min(x1, x2), y1 - t / 2, Math.max(x1, x2) + 1, y1 + (t + 1) / 2, col);
            return;
        }
        if (x1 == x2) {
            g.fill(x1 - t / 2, Math.min(y1, y2), x1 + (t + 1) / 2, Math.max(y1, y2) + 1, col);
            return;
        }
        // Diagonal fallback → orthogonal L (H then V) so wires never become stubs
        g.fill(Math.min(x1, x2), y1 - t / 2, Math.max(x1, x2) + 1, y1 + (t + 1) / 2, col);
        g.fill(x2 - t / 2, Math.min(y1, y2), x2 + (t + 1) / 2, Math.max(y1, y2) + 1, col);
    }

    private void drawWaypointHandles(GuiGraphics g, LogicEdge e) {
        int half = Math.max(3, Math.round(4 * zoom));
        for (int i = 0; i < e.waypoints.size(); i++) {
            LogicEdge.Point p = e.waypoints.get(i);
            int sx = Math.round(toScreenX(p.x));
            int sy = Math.round(toScreenY(p.y));
            boolean sel = i == selectedWaypointIndex;
            int fill = sel ? 0xFFFFD27A : 0xFFE8EEF4;
            int border = sel ? 0xFF0A0E12 : 0xFF3A4555;
            g.fill(sx - half - 1, sy - half - 1, sx + half + 1, sy + half + 1, border);
            g.fill(sx - half, sy - half, sx + half, sy + half, fill);
        }
    }

    private void drawNode(GuiGraphics g, LogicNode n, boolean sel) {
        int sx = Math.round(toScreenX(n.x));
        int sy = Math.round(toScreenY(n.y));
        int nw = nodeScreenW();
        int nh = nodeScreenH();
        int border = sel ? 0xFFE8EEF4 : 0xFF0A0E12;
        g.fill(sx - 1, sy - 1, sx + nw + 1, sy + nh + 1, border);
        fillRoundish(g, sx, sy, sx + nw, sy + nh, n.kind.color);
        g.fill(sx, sy, sx + nw, sy + Math.max(8, Math.round(14 * zoom)), 0x55000000);

        if (zoom >= 0.65f) {
            g.drawString(this.font, n.kind.label, sx + 6, sy + 3, TEXT, false);
            String sub = n.kind == NodeKind.CHEAT
                    ? "чит"
                    : (n.shortTarget().isEmpty() ? "—" : n.shortTarget());
            if (sub.length() > 15) {
                sub = sub.substring(0, 13) + "…";
            }
            g.drawString(this.font, sub, sx + 6, sy + Math.round(16 * zoom), 0xFFE0E8F0, false);
            String meta;
            if (n.kind == NodeKind.CHEAT) {
                meta = "r" + n.radius;
            } else if (needsRadius(n.kind) || (n.kind == NodeKind.IF && "has_near".equals(n.mode))) {
                meta = "×" + n.count + "  r" + n.radius;
            } else if (n.kind == NodeKind.IF) {
                meta = switch (n.mode) {
                    case "has_near" -> "блок поруч";
                    case "has_count" -> "≥" + n.count;
                    default -> "інвентар";
                };
            } else {
                meta = "×" + n.count;
            }
            g.drawString(this.font, meta, sx + 6, sy + Math.round(32 * zoom), 0xFFD0D8E0, false);
            if (n.kind == NodeKind.MINE && n.autoPickup && zoom >= 0.65f) {
                int cs = Math.max(8, Math.round(10 * zoom));
                drawCheckSprite(g, sx + nw - cs - 4, sy + Math.round(30 * zoom), cs, true);
            }
        }

        int portR = Math.max(2, Math.round(3 * zoom));
        int[] inPort = LogicGraph.portAnchor(n, LogicEdge.Port.OUT, false);
        drawPort(g, Math.round(toScreenX(inPort[0])), Math.round(toScreenY(inPort[1])), 0xFF4A9B9B, portR);
        if (n.hasBranchPorts()) {
            int[] truePort = LogicGraph.portAnchor(n, LogicEdge.Port.TRUE, true);
            int[] falsePort = LogicGraph.portAnchor(n, LogicEdge.Port.FALSE, true);
            drawPort(g, Math.round(toScreenX(truePort[0])), Math.round(toScreenY(truePort[1])), 0xFF6BCB8A, portR);
            drawPort(g, Math.round(toScreenX(falsePort[0])), Math.round(toScreenY(falsePort[1])), 0xFFE07A7A, portR);
            if (zoom >= 0.75f) {
                g.drawString(this.font, "так", sx + nw - 28, sy + Math.round(6 * zoom), 0xFF6BCB8A, false);
                g.drawString(this.font, "ні", sx + nw - 22, sy + nh - Math.round(16 * zoom), 0xFFE07A7A, false);
            }
        } else {
            int[] outPort = LogicGraph.portAnchor(n, LogicEdge.Port.OUT, true);
            drawPort(g, Math.round(toScreenX(outPort[0])), Math.round(toScreenY(outPort[1])), 0xFF8A9AAB, portR);
        }
    }

    private static void drawPort(GuiGraphics g, int x, int y, int col, int r) {
        g.fill(x - r - 1, y - r - 1, x + r + 1, y + r + 1, 0xFF0A0E12);
        g.fill(x - r, y - r, x + r, y + r, col);
    }

    private static double distToSeg(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0 && dy == 0) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = Mth.clamp(((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy), 0, 1);
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    private double distToEdgeScreen(LogicEdge e, int mx, int my) {
        double best = Double.MAX_VALUE;
        for (WireRouter.Seg s : edgeSegmentsWorld(e)) {
            int x1 = Math.round(toScreenX(s.x1()));
            int y1 = Math.round(toScreenY(s.y1()));
            int x2 = Math.round(toScreenX(s.x2()));
            int y2 = Math.round(toScreenY(s.y2()));
            best = Math.min(best, distToSeg(mx, my, x1, y1, x2, y2));
        }
        return best;
    }

    private LogicEdge hitEdge(int mx, int my) {
        LogicEdge best = null;
        double bestD = 8;
        for (LogicEdge e : graph.edges) {
            double d = distToEdgeScreen(e, mx, my);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private int hitWaypointIndex(LogicEdge e, int mx, int my) {
        int half = Math.max(5, Math.round(5 * zoom));
        for (int i = 0; i < e.waypoints.size(); i++) {
            LogicEdge.Point p = e.waypoints.get(i);
            int sx = Math.round(toScreenX(p.x));
            int sy = Math.round(toScreenY(p.y));
            if (mx >= sx - half && mx <= sx + half && my >= sy - half && my <= sy + half) {
                return i;
            }
        }
        return -1;
    }

    private void insertBendOnEdge(LogicEdge e, int mx, int my) {
        int[] ep = edgeEndpoints(e);
        if (ep == null) {
            return;
        }
        pushUndo();
        int wx = Math.round(toWorldX(mx));
        int wy = Math.round(toWorldY(my));
        WireRouter.insertBend(e, ep[0], ep[1], ep[2], ep[3], wx, wy);
        selectedEdge = e;
        selected = null;
        selectedWaypointIndex = -1;
        rebuildInspectorWidgets();
        toast("Точку згину додано (Alt+клік / 2×клік)", 35);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        int mx = (int) event.x();
        int my = (int) event.y();
        int button = event.button();

        if (button == 2 && onCanvas(mx, my)) {
            panning = true;
            panLastMx = mx;
            panLastMy = my;
            return true;
        }

        if (mx >= canvasRight() && button == 0) {
            if (selectedEdge != null && clickEdgeInspector(mx, my)) {
                return true;
            }
            if (selected != null && clickInspector(mx, my)) {
                rebuildInspectorWidgets();
                return true;
            }
        }

        if (mx < PALETTE_W && button == 0) {
            int py = 36 - scrollPalette;
            NodeKind.Category last = null;
            for (NodeKind kind : NodeKind.values()) {
                if (kind.category != last) {
                    last = kind.category;
                    py += 14;
                }
                if (my >= py && my < py + 20) {
                    pendingPlace = kind;
                    linkFrom = null;
                    selectedEdge = null;
                    selectedWaypointIndex = -1;
                    toast("Клікни на полотні: " + kind.label, 50);
                    return true;
                }
                py += 24;
            }
            return true;
        }

        if (!onCanvas(mx, my)) {
            return false;
        }

        if (pendingPlace != null && button == 0) {
            pushUndo();
            float wx = toWorldX(mx) - NODE_W / 2f;
            float wy = toWorldY(my) - NODE_H / 2f;
            LogicNode n = graph.add(pendingPlace, Math.round(wx), Math.round(wy));
            separateFromOthers(n);
            selected = n;
            selectedEdge = null;
            selectedWaypointIndex = -1;
            pendingPlace = null;
            rebuildInspectorWidgets();
            toast("Додано: " + n.kind.label, 35);
            return true;
        }

        if (selectedEdge != null && button == 0) {
            int wp = hitWaypointIndex(selectedEdge, mx, my);
            if (wp >= 0) {
                selectedWaypointIndex = wp;
                draggingWaypoint = true;
                if (!dragPushedUndo) {
                    pushUndo();
                    dragPushedUndo = true;
                }
                return true;
            }
        }

        LogicNode hit = hitNode(mx, my);
        if (hit != null) {
            if (button == 1) {
                LogicEdge.Port port = LogicEdge.Port.OUT;
                if (hit.hasBranchPorts()) {
                    float localY = toWorldY(my) - hit.y;
                    port = localY < NODE_H / 2f ? LogicEdge.Port.TRUE : LogicEdge.Port.FALSE;
                }
                if (linkFrom == null) {
                    linkFrom = hit;
                    linkPort = port;
                    selectedEdge = null;
                    selectedWaypointIndex = -1;
                    toast("Клікни вузол-ціль (" + portLabel(port) + ")", 40);
                } else {
                    pushUndo();
                    graph.connect(linkFrom.id, hit.id, linkPort);
                    toast("З'єднано", 30);
                    linkFrom = null;
                }
                return true;
            }
            if (button == 0) {
                if (linkFrom != null) {
                    pushUndo();
                    graph.connect(linkFrom.id, hit.id, linkPort);
                    toast("З'єднано", 30);
                    linkFrom = null;
                    return true;
                }
                selected = hit;
                selectedEdge = null;
                selectedWaypointIndex = -1;
                rebuildInspectorWidgets();
                dragging = hit;
                dragPushedUndo = false;
                dragOffX = toWorldX(mx) - hit.x;
                dragOffY = toWorldY(my) - hit.y;
                return true;
            }
        }

        LogicEdge edge = hitEdge(mx, my);
        if (edge != null) {
            if (button == 1 || (button == 0 && event.hasControlDown())) {
                pushUndo();
                graph.removeEdge(edge);
                if (selectedEdge == edge) {
                    selectedEdge = null;
                    selectedWaypointIndex = -1;
                }
                toast("Зв'язок видалено", 30);
                return true;
            }
            if (button == 0) {
                if (doubleClick || event.hasAltDown()) {
                    insertBendOnEdge(edge, mx, my);
                    return true;
                }
                selectedEdge = edge;
                selected = null;
                selectedWaypointIndex = -1;
                linkFrom = null;
                rebuildInspectorWidgets();
                toast("Зв'язок вибрано — Del · Alt+клік=згин", 35);
                return true;
            }
        }

        if (button == 0) {
            if (spaceHeld) {
                panning = true;
                panLastMx = mx;
                panLastMy = my;
                return true;
            }
            canvasPanCandidate = true;
            canvasPanning = false;
            panStartMx = mx;
            panStartMy = my;
            panLastMx = mx;
            panLastMy = my;
            return true;
        }
        if (button == 1) {
            linkFrom = null;
            pendingPlace = null;
        }
        return false;
    }

    private boolean clickEdgeInspector(int mx, int my) {
        int ix = canvasRight() + 10;
        int iy = 28 - scrollInspect + 14 + 14 + 14 + 18;
        if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 22)) {
            pushUndo();
            rerouteEdgeAround(selectedEdge);
            toast("Маршрут обійшов вузли", 35);
            return true;
        }
        return false;
    }

    private boolean clickInspector(int mx, int my) {
        int ix = canvasRight() + 10;
        int iy = 28 - scrollInspect;
        // Must match drawInspector header: title (+14), then shortTarget (+18) or spacer (+4)
        iy += 14;
        if (selected.kind == NodeKind.AREA || selected.kind == NodeKind.START
                || selected.kind == NodeKind.END || selected.kind == NodeKind.CHEAT) {
            iy += 4;
        } else {
            iy += 18;
        }

        if (selected.kind == NodeKind.AREA) {
            iy += 12; // "Тип зони" label
            for (TargetPresets.Preset p : TargetPresets.AREA_MODES) {
                if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 18)) {
                    pushUndo();
                    selected.mode = p.id();
                    toast(p.label(), 30);
                    return true;
                }
                iy += 22;
            }
            iy += 4;
            if ("box".equals(selected.mode)) {
                iy += 12; // "Кут A"
                if (clickStat(mx, my, ix, iy, selected.posX, v -> { pushUndo(); selected.posX = v; }, -30_000_000, 30_000_000)) {
                    return true;
                }
                iy += 22;
                if (clickStat(mx, my, ix, iy, selected.posY, v -> { pushUndo(); selected.posY = v; }, -64, 320)) {
                    return true;
                }
                iy += 22;
                if (clickStat(mx, my, ix, iy, selected.posZ, v -> { pushUndo(); selected.posZ = v; }, -30_000_000, 30_000_000)) {
                    return true;
                }
                iy += 22;
                if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 18)) {
                    pushUndo();
                    selected.snapCoordsFromPlayer();
                    toast("XYZ₁ " + selected.posX + " " + selected.posY + " " + selected.posZ, 40);
                    return true;
                }
                iy += 26 + 12; // button + "Кут B"
                if (clickStat(mx, my, ix, iy, selected.posX2, v -> { pushUndo(); selected.posX2 = v; }, -30_000_000, 30_000_000)) {
                    return true;
                }
                iy += 22;
                if (clickStat(mx, my, ix, iy, selected.posY2, v -> { pushUndo(); selected.posY2 = v; }, -64, 320)) {
                    return true;
                }
                iy += 22;
                if (clickStat(mx, my, ix, iy, selected.posZ2, v -> { pushUndo(); selected.posZ2 = v; }, -30_000_000, 30_000_000)) {
                    return true;
                }
                iy += 22;
                if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 18)) {
                    pushUndo();
                    selected.snapCoords2FromPlayer();
                    toast("XYZ₂ " + selected.posX2 + " " + selected.posY2 + " " + selected.posZ2, 40);
                    return true;
                }
            } else {
                iy += 12; // "Центр"
                if (clickStat(mx, my, ix, iy, selected.posX, v -> { pushUndo(); selected.posX = v; }, -30_000_000, 30_000_000)) {
                    return true;
                }
                iy += 22;
                if (clickStat(mx, my, ix, iy, selected.posY, v -> { pushUndo(); selected.posY = v; }, -64, 320)) {
                    return true;
                }
                iy += 22;
                if (clickStat(mx, my, ix, iy, selected.posZ, v -> { pushUndo(); selected.posZ = v; }, -30_000_000, 30_000_000)) {
                    return true;
                }
                iy += 22;
                if (clickStat(mx, my, ix, iy, selected.radius, v -> { pushUndo(); selected.radius = v; }, 1, 256)) {
                    toast("радіус=" + selected.radius, 20);
                    return true;
                }
                iy += 22;
                if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 18)) {
                    pushUndo();
                    selected.snapCoordsFromPlayer();
                    toast("центр " + selected.posX + " " + selected.posY + " " + selected.posZ, 40);
                    return true;
                }
            }
            return false;
        }

        if (selected.kind == NodeKind.CHEAT) {
            iy += 48; // help text
            if (clickStat(mx, my, ix, iy, selected.radius, v -> {
                pushUndo();
                selected.radius = v;
            }, 16, 256)) {
                toast("чит r=" + selected.radius, 20);
                return true;
            }
            return false;
        }

        if (selected.kind == NodeKind.START || selected.kind == NodeKind.END) {
            return false;
        }

        if (clickStat(mx, my, ix, iy, selected.count, v -> {
            pushUndo();
            selected.count = v;
        }, 1, 64)) {
            toast("кількість=" + selected.count, 20);
            return true;
        }
        iy += 22;
        boolean showR = needsRadius(selected.kind) || (selected.kind == NodeKind.IF && "has_near".equals(selected.mode))
                || (selected.kind == NodeKind.PLACE && !"coords".equals(selected.mode));
        if (showR) {
            if (clickStat(mx, my, ix, iy, selected.radius, v -> {
                pushUndo();
                selected.radius = v;
            }, 1, 64)) {
                toast("радіус=" + selected.radius, 20);
                return true;
            }
            iy += 22;
        }
        if (selected.kind == NodeKind.MINE) {
            if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 18)) {
                pushUndo();
                selected.autoPickup = !selected.autoPickup;
                toast(selected.autoPickup ? "Підбір дропу ВКЛ" : "Підбір дропу ВИКЛ", 30);
                return true;
            }
            iy += 22;
        }
        iy += 6;

        if (selected.kind == NodeKind.PLACE) {
            iy += 12;
            for (TargetPresets.Preset p : TargetPresets.PLACE_MODES) {
                if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 18)) {
                    pushUndo();
                    selected.mode = p.id();
                    toast(p.label(), 30);
                    return true;
                }
                iy += 22;
            }
            if ("coords".equals(selected.mode)) {
                iy += 2;
                if (clickStat(mx, my, ix, iy, selected.posX, v -> {
                    pushUndo();
                    selected.posX = v;
                }, -30_000_000, 30_000_000)) {
                    toast("X=" + selected.posX, 15);
                    return true;
                }
                iy += 22;
                if (clickStat(mx, my, ix, iy, selected.posY, v -> {
                    pushUndo();
                    selected.posY = v;
                }, -64, 320)) {
                    toast("Y=" + selected.posY, 15);
                    return true;
                }
                iy += 22;
                if (clickStat(mx, my, ix, iy, selected.posZ, v -> {
                    pushUndo();
                    selected.posZ = v;
                }, -30_000_000, 30_000_000)) {
                    toast("Z=" + selected.posZ, 15);
                    return true;
                }
                iy += 22;
                if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 18)) {
                    var p = net.minecraft.client.Minecraft.getInstance().player;
                    if (p != null) {
                        pushUndo();
                        var bp = p.blockPosition();
                        selected.posX = bp.getX();
                        selected.posY = bp.getY();
                        selected.posZ = bp.getZ();
                        toast("XYZ " + selected.posX + " " + selected.posY + " " + selected.posZ, 40);
                    }
                    return true;
                }
                iy += 22;
            }
            iy += 4 + 12;
            for (TargetPresets.Preset p : TargetPresets.forKind(selected.kind)) {
                if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 16)) {
                    pushUndo();
                    selected.target = p.id();
                    targetBox.setValue(p.id());
                    toast(p.label(), 25);
                    return true;
                }
                iy += 20;
            }
        } else if (selected.kind == NodeKind.IF) {
            iy += 12;
            for (TargetPresets.Preset p : TargetPresets.IF_MODES) {
                if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 18)) {
                    pushUndo();
                    selected.mode = p.id();
                    toast(p.label(), 30);
                    return true;
                }
                iy += 22;
            }
            iy += 4 + 12;
            for (TargetPresets.Preset p : TargetPresets.targetsForIfMode(selected.mode)) {
                if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 16)) {
                    pushUndo();
                    selected.target = p.id();
                    targetBox.setValue(p.id());
                    toast(p.label(), 25);
                    return true;
                }
                iy += 20;
            }
        } else {
            List<TargetPresets.Preset> presets = TargetPresets.forKind(selected.kind);
            if (!presets.isEmpty()) {
                iy += 12;
                for (TargetPresets.Preset p : presets) {
                    if (hitBox(mx, my, ix, iy, INSPECT_W - 20, 16)) {
                        pushUndo();
                        selected.target = p.id();
                        targetBox.setValue(p.id());
                        toast(p.label(), 25);
                        return true;
                    }
                    iy += 20;
                }
            }
        }
        return false;
    }

    private boolean clickStat(int mx, int my, int ix, int iy, int value, java.util.function.IntConsumer set, int min, int max) {
        int bx = ix + INSPECT_W - 62;
        if (hitBox(mx, my, bx, iy, 20, 16)) {
            set.accept(Mth.clamp(value - 1, min, max));
            return true;
        }
        if (hitBox(mx, my, bx + 24, iy, 20, 16)) {
            set.accept(Mth.clamp(value + 1, min, max));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (panning && (event.button() == 2 || (event.button() == 0 && spaceHeld))) {
            panX += (float) (event.x() - panLastMx);
            panY += (float) (event.y() - panLastMy);
            panLastMx = event.x();
            panLastMy = event.y();
            return true;
        }

        if (canvasPanCandidate && event.button() == 0) {
            double moved = Math.hypot(event.x() - panStartMx, event.y() - panStartMy);
            if (moved > 3) {
                canvasPanning = true;
            }
            if (canvasPanning) {
                panX += (float) (event.x() - panLastMx);
                panY += (float) (event.y() - panLastMy);
                panLastMx = event.x();
                panLastMy = event.y();
                return true;
            }
        }

        if (draggingWaypoint && selectedEdge != null && selectedWaypointIndex >= 0 && event.button() == 0) {
            LogicEdge.Point p = selectedEdge.waypoints.get(selectedWaypointIndex);
            p.x = Math.round(toWorldX(event.x()));
            p.y = Math.round(toWorldY(event.y()));
            return true;
        }

        if (dragging != null && event.button() == 0) {
            if (!dragPushedUndo) {
                pushUndo();
                dragPushedUndo = true;
            }
            dragging.x = Math.round(toWorldX(event.x()) - dragOffX);
            dragging.y = Math.round(toWorldY(event.y()) - dragOffY);
            separateFromOthers(dragging);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingWaypoint && selectedEdge != null) {
            orthogonalizeEdge(selectedEdge);
            draggingWaypoint = false;
            dragPushedUndo = false;
        }

        if (dragging != null) {
            separateFromOthers(dragging);
            rerouteEdgesTouching(dragging);
        }

        if (canvasPanCandidate && !canvasPanning && event.button() == 0) {
            selected = null;
            selectedEdge = null;
            selectedWaypointIndex = -1;
            linkFrom = null;
            rebuildInspectorWidgets();
        }

        dragging = null;
        panning = false;
        canvasPanCandidate = false;
        canvasPanning = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < PALETTE_W) {
            scrollPalette = Mth.clamp(scrollPalette - (int) (scrollY * 14), 0, 520);
            return true;
        }
        if (mouseX >= canvasRight()) {
            scrollInspect = Mth.clamp(scrollInspect - (int) (scrollY * 14), 0, 400);
            return true;
        }
        if (onCanvas(mouseX, mouseY) && scrollY != 0) {
            if (hasShift() && selected != null) {
                pushUndo();
                if (selected.kind == NodeKind.CHEAT) {
                    selected.radius = Mth.clamp(selected.radius + (scrollY > 0 ? 8 : -8), 16, 256);
                    toast("чит r=" + selected.radius, 20);
                } else if (needsRadius(selected.kind) || (selected.kind == NodeKind.IF && "has_near".equals(selected.mode))
                        || (selected.kind == NodeKind.PLACE && !"coords".equals(selected.mode))) {
                    selected.radius = Mth.clamp(selected.radius + (scrollY > 0 ? 1 : -1), 1, 64);
                    toast("радіус=" + selected.radius, 20);
                } else {
                    selected.count = Mth.clamp(selected.count + (scrollY > 0 ? 1 : -1), 1, 64);
                    toast("кількість=" + selected.count, 20);
                }
                return true;
            }
            float factor = scrollY > 0 ? 1.1f : 1f / 1.1f;
            zoomAtCursor(mouseX, mouseY, factor);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean hasShift() {
        return this.minecraft != null && this.minecraft.hasShiftDown();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Global shortcuts — work even while the target EditBox is focused
        boolean ctrl = event.hasControlDown() || event.hasControlDownWithQuirk();
        if (event.key() == GLFW.GLFW_KEY_Z && ctrl && !event.hasAltDown()) {
            if (targetBox != null) {
                targetBox.setFocused(false);
            }
            undo();
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_DELETE) {
            if (selectedEdge != null && selectedWaypointIndex >= 0) {
                pushUndo();
                selectedEdge.waypoints.remove(selectedWaypointIndex);
                orthogonalizeEdge(selectedEdge);
                selectedWaypointIndex = -1;
                toast("Точку згину видалено", 30);
                return true;
            }
            if (selectedEdge != null) {
                pushUndo();
                graph.removeEdge(selectedEdge);
                selectedEdge = null;
                selectedWaypointIndex = -1;
                toast("Зв'язок видалено", 30);
                return true;
            }
            if (selected != null && selected.kind != NodeKind.START) {
                pushUndo();
                graph.remove(selected.id);
                selected = null;
                if (targetBox != null) {
                    targetBox.setFocused(false);
                }
                rebuildInspectorWidgets();
                toast("Ноду видалено", 30);
                return true;
            }
        }
        // While typing target/condition — Backspace & letters go to the field
        if (targetBox != null && targetBox.isFocused()) {
            return super.keyPressed(event);
        }
        if (event.key() == GLFW.GLFW_KEY_SPACE) {
            spaceHeld = true;
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (pendingPlace != null || linkFrom != null) {
                pendingPlace = null;
                linkFrom = null;
                toast("Скасовано", 20);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_SPACE) {
            spaceHeld = false;
            return true;
        }
        return super.keyReleased(event);
    }

    private LogicNode hitNode(int mx, int my) {
        float wx = toWorldX(mx);
        float wy = toWorldY(my);
        List<LogicNode> rev = new ArrayList<>(graph.nodes);
        for (int i = rev.size() - 1; i >= 0; i--) {
            LogicNode n = rev.get(i);
            if (wx >= n.x && wx <= n.x + NODE_W && wy >= n.y && wy <= n.y + NODE_H) {
                return n;
            }
        }
        return null;
    }

    @Override
    public void onClose() {
        LogicGraphStore.setCurrent(graph);
        LogicGraphStore.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
