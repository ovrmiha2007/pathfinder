package com.lecternscanner.client.logic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.lecternscanner.LecternScannerMod;

/**
 * Preset targets for the inspector picker. Defaults + user custom (saved to disk).
 */
public final class TargetPresets {
    public record Preset(String id, String label) {
        public Preset {
            id = id == null ? "" : id;
            label = (label == null || label.isBlank()) ? shortLabel(id) : label;
        }

        private static String shortLabel(String id) {
            if (id == null || id.isEmpty()) {
                return "?";
            }
            String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            if (s.startsWith("#")) {
                s = s.substring(1);
            }
            return s;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final List<Preset> DEFAULT_BLOCKS = List.of(
            new Preset("#minecraft:logs", "Дерево (логи)"),
            new Preset("minecraft:oak_log", "Дуб"),
            new Preset("minecraft:cobblestone", "Булижник"),
            new Preset("minecraft:stone", "Камінь"),
            new Preset("minecraft:dirt", "Земля"),
            new Preset("minecraft:crafting_table", "Верстак"),
            new Preset("minecraft:furnace", "Піч"),
            new Preset("minecraft:chest", "Скриня"),
            new Preset("minecraft:iron_ore", "Залізна руда"),
            new Preset("minecraft:coal_ore", "Вугільна руда"),
            new Preset("minecraft:sand", "Пісок"),
            new Preset("minecraft:water", "Вода")
    );

    public static final List<Preset> DEFAULT_ITEMS = List.of(
            new Preset("minecraft:crafting_table", "Верстак"),
            new Preset("minecraft:furnace", "Піч"),
            new Preset("minecraft:oak_planks", "Дошки"),
            new Preset("#minecraft:logs", "Логи"),
            new Preset("minecraft:stick", "Палиці"),
            new Preset("minecraft:cobblestone", "Булижник"),
            new Preset("minecraft:iron_ingot", "Залізо"),
            new Preset("minecraft:coal", "Вугілля"),
            new Preset("minecraft:wooden_pickaxe", "Дерев. кирка"),
            new Preset("minecraft:stone_pickaxe", "Кам. кирка"),
            new Preset("minecraft:wooden_axe", "Дерев. сокира"),
            new Preset("minecraft:bread", "Хліб")
    );

    /** Mutable working lists (defaults + custom). */
    private static final List<Preset> BLOCKS = new ArrayList<>(DEFAULT_BLOCKS);
    private static final List<Preset> ITEMS = new ArrayList<>(DEFAULT_ITEMS);

    public static final List<Preset> AREA_MODES = List.of(
            new Preset("radius", "Центр + радіус"),
            new Preset("box", "Від XYZ₁ до XYZ₂")
    );

    public static final List<Preset> PLACE_MODES = List.of(
            new Preset("near", "Найближче місце"),
            new Preset("coords", "Координати XYZ")
    );

    public static final List<Preset> IF_MODES = List.of(
            new Preset("has_item", "Є предмет в інвентарі"),
            new Preset("has_near", "Блок у радіусі"),
            new Preset("has_count", "Кількість предмета ≥")
    );

    public static final List<Preset> FOLLOW_MODES = List.of(
            new Preset("player", "Гравець"),
            new Preset("entity", "Сутність")
    );

    public static final List<Preset> FOLLOW_ENTITIES = List.of(
            new Preset("any", "Будь-яка жива"),
            new Preset("hostile", "Вороги"),
            new Preset("animal", "Тварини"),
            new Preset("minecraft:zombie", "Зомбі"),
            new Preset("minecraft:skeleton", "Скелет"),
            new Preset("minecraft:creeper", "Кріпер"),
            new Preset("minecraft:cow", "Корова"),
            new Preset("minecraft:pig", "Свиня"),
            new Preset("minecraft:sheep", "Вівця"),
            new Preset("minecraft:villager", "Селянин")
    );

    private static boolean loaded;

    private TargetPresets() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        load();
    }

    public static Path presetsFile() {
        Path root = LogicGraphStore.gameDir();
        return root == null ? null : root.resolve("pathfinder").resolve("presets.json");
    }

    public static synchronized List<Preset> blocks() {
        ensureLoaded();
        return List.copyOf(BLOCKS);
    }

    public static synchronized List<Preset> items() {
        ensureLoaded();
        return List.copyOf(ITEMS);
    }

    public static List<Preset> forKind(NodeKind kind) {
        ensureLoaded();
        return switch (kind) {
            case HAS_ITEM, CRAFT, PLACE, SMELT, TAKE_FROM, PICKUP -> items();
            case FIND_BLOCK, MINE, HAS_NEAR, IN_RADIUS, GOTO -> blocks();
            case FOLLOW -> FOLLOW_ENTITIES;
            case IF -> IF_MODES;
            default -> List.of();
        };
    }

    public static List<Preset> targetsForIfMode(String mode) {
        ensureLoaded();
        if ("has_near".equals(mode)) {
            return blocks();
        }
        return items();
    }

    public enum ListKind { BLOCK, ITEM }

    public static ListKind listKindFor(LogicNode node) {
        if (node == null) {
            return ListKind.BLOCK;
        }
        return switch (node.kind) {
            case HAS_ITEM, CRAFT, PLACE, SMELT, TAKE_FROM, PICKUP -> ListKind.ITEM;
            case IF -> "has_near".equals(node.mode) ? ListKind.BLOCK : ListKind.ITEM;
            default -> ListKind.BLOCK;
        };
    }

    public static synchronized boolean addPreset(ListKind kind, String id, String label) {
        ensureLoaded();
        if (id == null || id.isBlank()) {
            return false;
        }
        String clean = id.trim();
        List<Preset> list = kind == ListKind.ITEM ? ITEMS : BLOCKS;
        list.removeIf(p -> p.id().equalsIgnoreCase(clean));
        list.add(new Preset(clean, label));
        save();
        return true;
    }

    public static synchronized boolean removePreset(ListKind kind, String id) {
        ensureLoaded();
        if (id == null || id.isBlank()) {
            return false;
        }
        List<Preset> list = kind == ListKind.ITEM ? ITEMS : BLOCKS;
        boolean removed = list.removeIf(p -> p.id().equalsIgnoreCase(id.trim()));
        if (removed) {
            save();
        }
        return removed;
    }

    public static synchronized void restoreDefaults() {
        BLOCKS.clear();
        BLOCKS.addAll(DEFAULT_BLOCKS);
        ITEMS.clear();
        ITEMS.addAll(DEFAULT_ITEMS);
        save();
    }

    public static synchronized void load() {
        Path file = presetsFile();
        if (file == null || !Files.isRegularFile(file)) {
            BLOCKS.clear();
            BLOCKS.addAll(DEFAULT_BLOCKS);
            ITEMS.clear();
            ITEMS.addAll(DEFAULT_ITEMS);
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> map = GSON.fromJson(json, new TypeToken<Map<String, Object>>() {
            }.getType());
            if (map == null) {
                return;
            }
            BLOCKS.clear();
            ITEMS.clear();
            BLOCKS.addAll(parseList(map.get("blocks"), DEFAULT_BLOCKS));
            ITEMS.addAll(parseList(map.get("items"), DEFAULT_ITEMS));
            if (BLOCKS.isEmpty()) {
                BLOCKS.addAll(DEFAULT_BLOCKS);
            }
            if (ITEMS.isEmpty()) {
                ITEMS.addAll(DEFAULT_ITEMS);
            }
        } catch (Exception e) {
            LecternScannerMod.LOGGER.warn("Failed to load presets", e);
            BLOCKS.clear();
            BLOCKS.addAll(DEFAULT_BLOCKS);
            ITEMS.clear();
            ITEMS.addAll(DEFAULT_ITEMS);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Preset> parseList(Object raw, List<Preset> fallback) {
        List<Preset> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>(fallback);
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object id = m.get("id");
                Object label = m.get("label");
                if (id != null) {
                    out.add(new Preset(String.valueOf(id), label == null ? null : String.valueOf(label)));
                }
            } else if (o instanceof String s) {
                out.add(new Preset(s, null));
            }
        }
        return out;
    }

    public static synchronized void save() {
        Path file = presetsFile();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("blocks", toMaps(BLOCKS));
            map.put("items", toMaps(ITEMS));
            Files.writeString(file, GSON.toJson(map), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LecternScannerMod.LOGGER.warn("Failed to save presets", e);
        }
    }

    private static List<Map<String, String>> toMaps(List<Preset> list) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Preset p : list) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("label", p.label());
            out.add(m);
        }
        return out;
    }

    public static String suggestLabel(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String s = id.trim();
        if (s.startsWith("#")) {
            String t = s.substring(1);
            if (t.contains(":")) {
                t = t.substring(t.indexOf(':') + 1);
            }
            return t.replace('_', ' ');
        }
        String name = s.contains(":") ? s.substring(s.indexOf(':') + 1) : s;
        return name.replace('_', ' ');
    }
}
