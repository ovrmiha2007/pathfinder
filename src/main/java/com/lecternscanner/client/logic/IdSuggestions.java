package com.lecternscanner.client.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

/**
 * Command-style id suggestions for the target EditBox.
 */
public final class IdSuggestions {
    public enum Pool { BLOCK, ITEM, ENTITY, PLAYER, ANY }

    private IdSuggestions() {
    }

    public static Pool poolFor(LogicNode node) {
        if (node == null) {
            return Pool.ANY;
        }
        return switch (node.kind) {
            case FOLLOW -> "player".equals(node.mode) ? Pool.PLAYER : Pool.ENTITY;
            case FIND_BLOCK, MINE, HAS_NEAR, IN_RADIUS, GOTO -> Pool.BLOCK;
            case HAS_ITEM, CRAFT, PLACE, SMELT, TAKE_FROM, PICKUP -> Pool.ITEM;
            case IF -> "has_near".equals(node.mode) ? Pool.BLOCK : Pool.ITEM;
            default -> Pool.ANY;
        };
    }

    public static List<String> suggest(String typed, Pool pool, int limit) {
        String q = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        List<Scored> scored = new ArrayList<>();

        if (pool == Pool.PLAYER || pool == Pool.ANY) {
            addPlayers(scored, q);
        }
        if (pool == Pool.BLOCK || pool == Pool.ANY) {
            addRegistry(scored, q, true);
            addCommonTags(scored, q, true);
        }
        if (pool == Pool.ITEM || pool == Pool.ANY) {
            addRegistry(scored, q, false);
            addCommonTags(scored, q, false);
        }
        if (pool == Pool.ENTITY || pool == Pool.ANY) {
            addEntities(scored, q);
            if (q.isEmpty() || "any".startsWith(q) || "hostile".startsWith(q) || "animal".startsWith(q)) {
                bump(scored, "any", q);
                bump(scored, "hostile", q);
                bump(scored, "animal", q);
            }
        }

        scored.sort(Comparator
                .comparingInt((Scored s) -> s.score)
                .thenComparing(s -> s.id));
        List<String> out = new ArrayList<>();
        for (Scored s : scored) {
            if (!out.contains(s.id)) {
                out.add(s.id);
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static void addPlayers(List<Scored> out, String q) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer self = mc.player;
        if (level == null) {
            return;
        }
        for (Player p : level.players()) {
            if (p == self) {
                continue;
            }
            String name = p.getName().getString();
            bump(out, name, q);
        }
    }

    private static void addRegistry(List<Scored> out, String q, boolean blocks) {
        if (blocks) {
            for (var block : BuiltInRegistries.BLOCK) {
                Identifier id = BuiltInRegistries.BLOCK.getKey(block);
                if (id != null) {
                    bump(out, id.toString(), q);
                }
            }
        } else {
            for (var item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null) {
                    bump(out, id.toString(), q);
                }
            }
        }
    }

    private static void addEntities(List<Scored> out, String q) {
        for (var type : BuiltInRegistries.ENTITY_TYPE) {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id != null) {
                bump(out, id.toString(), q);
            }
        }
    }

    private static void addCommonTags(List<Scored> out, String q, boolean blocks) {
        String[] tags = blocks
                ? new String[]{"#minecraft:logs", "#minecraft:planks", "#minecraft:stone", "#minecraft:ores",
                "#minecraft:coal_ores", "#minecraft:iron_ores", "#minecraft:copper_ores", "#minecraft:dirt",
                "#minecraft:sand", "#minecraft:base_stone_overworld"}
                : new String[]{"#minecraft:logs", "#minecraft:planks", "#minecraft:coals", "#minecraft:wool",
                "#minecraft:boats", "#minecraft:pickaxes", "#minecraft:axes", "#minecraft:swords"};
        for (String t : tags) {
            bump(out, t, q);
        }
    }

    private static void bump(List<Scored> out, String id, String q) {
        int score = scoreMatch(id, q);
        if (score < 0) {
            return;
        }
        out.add(new Scored(id, score));
    }

    /** Lower is better; -1 = no match. Empty query → mild score so defaults still sort. */
    private static int scoreMatch(String id, String q) {
        String low = id.toLowerCase(Locale.ROOT);
        String shortName = low.contains(":") ? low.substring(low.indexOf(':') + 1) : low;
        if (q.isEmpty()) {
            return 500 + Math.min(shortName.length(), 40);
        }
        if (low.equals(q) || shortName.equals(q)) {
            return 0;
        }
        if (shortName.startsWith(q)) {
            return 10;
        }
        if (low.startsWith(q)) {
            return 20;
        }
        if (shortName.contains(q)) {
            return 40;
        }
        if (low.contains(q)) {
            return 60;
        }
        // typed without namespace: "oak_log"
        if (!q.contains(":") && shortName.startsWith(q)) {
            return 15;
        }
        return -1;
    }

    private record Scored(String id, int score) {
    }
}
