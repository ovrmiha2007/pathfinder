package com.lecternscanner.client.logic;

import java.util.List;

/** Preset targets for the inspector picker. */
public final class TargetPresets {
    public record Preset(String id, String label) {
    }

    public static final List<Preset> BLOCKS = List.of(
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

    public static final List<Preset> ITEMS = List.of(
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

    private TargetPresets() {
    }

    public static List<Preset> forKind(NodeKind kind) {
        return switch (kind) {
            case HAS_ITEM, CRAFT, PLACE, SMELT, TAKE_FROM, PICKUP -> ITEMS;
            case FIND_BLOCK, MINE, HAS_NEAR, IN_RADIUS, GOTO -> BLOCKS;
            case IF -> IF_MODES;
            default -> List.of();
        };
    }

    public static List<Preset> targetsForIfMode(String mode) {
        if ("has_near".equals(mode)) {
            return BLOCKS;
        }
        return ITEMS;
    }
}
