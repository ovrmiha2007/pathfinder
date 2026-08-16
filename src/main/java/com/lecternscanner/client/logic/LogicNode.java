package com.lecternscanner.client.logic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * One draggable node on the logic canvas.
 */
public final class LogicNode {
    public final String id;
    public NodeKind kind;
    /** Item / block id, e.g. {@code minecraft:crafting_table} or tag {@code #minecraft:logs}. */
    public String target = "";
    /**
     * IF: has_item | has_near | has_count
     * PLACE: near | coords
     * AREA: radius | box
     */
    public String mode = "has_item";
    public int count = 1;
    public int radius = 16;
    /** MINE: walk onto drops after breaking. */
    public boolean autoPickup = true;
    /** PLACE / AREA: primary coordinates (center or corner A). */
    public int posX;
    public int posY = 64;
    public int posZ;
    /** AREA box mode: corner B. */
    public int posX2;
    public int posY2 = 64;
    public int posZ2;
    public int x;
    public int y;

    public LogicNode(NodeKind kind, int x, int y) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.kind = kind;
        this.x = x;
        this.y = y;
        defaultsForKind();
    }

    public LogicNode(String id, NodeKind kind, String target, String mode, int count, int radius,
                     boolean autoPickup, int posX, int posY, int posZ,
                     int posX2, int posY2, int posZ2, int x, int y) {
        this.id = id;
        this.kind = kind;
        this.target = target == null ? "" : target;
        this.mode = mode == null || mode.isEmpty() ? defaultMode(kind) : mode;
        this.count = count;
        this.radius = radius;
        this.autoPickup = autoPickup;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.posX2 = posX2;
        this.posY2 = posY2;
        this.posZ2 = posZ2;
        this.x = x;
        this.y = y;
    }

    private static String defaultMode(NodeKind kind) {
        return switch (kind) {
            case PLACE -> "near";
            case AREA -> "radius";
            default -> "has_item";
        };
    }

    private void defaultsForKind() {
        switch (kind) {
            case PLACE -> {
                target = "minecraft:crafting_table";
                mode = "near";
                radius = 4;
            }
            case AREA -> {
                mode = "radius";
                radius = 32;
                snapCoordsFromPlayer();
                posX2 = posX + 16;
                posY2 = posY;
                posZ2 = posZ + 16;
            }
            case CRAFT -> target = "minecraft:oak_planks";
            case MINE, FIND_BLOCK, HAS_NEAR, IN_RADIUS -> {
                target = "#minecraft:logs";
                radius = 24;
                if (kind == NodeKind.MINE) {
                    autoPickup = true;
                }
            }
            case PICKUP -> {
                target = "";
                radius = 8;
                count = 1;
            }
            case HAS_ITEM -> target = "minecraft:crafting_table";
            case IF -> {
                mode = "has_item";
                target = "minecraft:crafting_table";
            }
            case GOTO -> {
                target = "minecraft:crafting_table";
                radius = 32;
            }
            case END -> {
                target = "";
                mode = "";
            }
            default -> {
            }
        }
    }

    public void snapCoordsFromPlayer() {
        var p = Minecraft.getInstance().player;
        if (p != null) {
            BlockPos bp = p.blockPosition();
            posX = bp.getX();
            posY = bp.getY();
            posZ = bp.getZ();
        }
    }

    public void snapCoords2FromPlayer() {
        var p = Minecraft.getInstance().player;
        if (p != null) {
            BlockPos bp = p.blockPosition();
            posX2 = bp.getX();
            posY2 = bp.getY();
            posZ2 = bp.getZ();
        }
    }

    public String title() {
        if (kind == NodeKind.END) {
            return kind.label;
        }
        if (kind == NodeKind.AREA) {
            if ("box".equals(mode)) {
                return kind.label + " [" + posX + "," + posY + "," + posZ
                        + " → " + posX2 + "," + posY2 + "," + posZ2 + "]";
            }
            return kind.label + " @" + posX + " " + posY + " " + posZ + " r=" + radius;
        }
        if (kind == NodeKind.PLACE) {
            String shortT = shortTarget();
            String where = "coords".equals(mode)
                    ? (posX + " " + posY + " " + posZ)
                    : ("поруч r=" + radius);
            return kind.label + (shortT.isEmpty() ? "" : ": " + shortT) + " @ " + where;
        }
        if (kind == NodeKind.IF) {
            String m = switch (mode) {
                case "has_near" -> "блок поруч";
                case "has_count" -> "кількість ≥";
                default -> "є предмет";
            };
            String shortT = shortTarget();
            return kind.label + " (" + m + (shortT.isEmpty() ? "" : ": " + shortT) + ")";
        }
        if (kind == NodeKind.MINE && autoPickup) {
            String shortT = shortTarget();
            return kind.label + (shortT.isEmpty() ? "" : ": " + shortT) + " +підбір";
        }
        if (kind == NodeKind.PICKUP) {
            String shortT = shortTarget();
            return shortT.isEmpty() ? kind.label + " (будь-що)" : kind.label + ": " + shortT;
        }
        String shortT = shortTarget();
        if (shortT.isEmpty()) {
            return kind.label;
        }
        return kind.label + ": " + shortT;
    }

    public String shortTarget() {
        if (target == null || target.isEmpty()) {
            return "";
        }
        String shortName = target.contains(":") ? target.substring(target.indexOf(':') + 1) : target;
        if (shortName.startsWith("#")) {
            shortName = shortName.substring(1);
        }
        return shortName;
    }

    public boolean hasBranchPorts() {
        return kind == NodeKind.IF
                || kind == NodeKind.HAS_ITEM
                || kind == NodeKind.HAS_NEAR
                || kind == NodeKind.FIND_BLOCK
                || kind == NodeKind.IN_RADIUS
                || kind == NodeKind.PICKUP;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind.name());
        m.put("target", target);
        m.put("mode", mode);
        m.put("count", count);
        m.put("radius", radius);
        m.put("autoPickup", autoPickup);
        m.put("posX", posX);
        m.put("posY", posY);
        m.put("posZ", posZ);
        m.put("posX2", posX2);
        m.put("posY2", posY2);
        m.put("posZ2", posZ2);
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    public static LogicNode fromMap(Map<String, Object> m) {
        String kindRaw = String.valueOf(m.get("kind"));
        // Drop removed nodes from old schemes
        if ("REQUIRED".equals(kindRaw) || "OPTIONAL".equals(kindRaw) || "ELSE".equals(kindRaw)) {
            return null;
        }
        NodeKind kind = NodeKind.parse(kindRaw);
        boolean pickup = true;
        if (m.containsKey("autoPickup")) {
            Object v = m.get("autoPickup");
            if (v instanceof Boolean b) {
                pickup = b;
            } else {
                pickup = Boolean.parseBoolean(String.valueOf(v));
            }
        } else if (kind != NodeKind.MINE) {
            pickup = false;
        }
        String mode = String.valueOf(m.getOrDefault("mode", defaultMode(kind)));
        if (kind == NodeKind.PLACE && ("has_item".equals(mode) || mode.isEmpty() || "null".equals(mode))) {
            mode = "near";
        }
        if (kind == NodeKind.AREA && ("has_item".equals(mode) || mode.isEmpty() || "null".equals(mode))) {
            mode = "radius";
        }
        return new LogicNode(
                String.valueOf(m.get("id")),
                kind,
                String.valueOf(m.getOrDefault("target", "")),
                mode,
                ((Number) m.getOrDefault("count", 1)).intValue(),
                ((Number) m.getOrDefault("radius", 16)).intValue(),
                pickup,
                ((Number) m.getOrDefault("posX", 0)).intValue(),
                ((Number) m.getOrDefault("posY", 64)).intValue(),
                ((Number) m.getOrDefault("posZ", 0)).intValue(),
                ((Number) m.getOrDefault("posX2", 0)).intValue(),
                ((Number) m.getOrDefault("posY2", 64)).intValue(),
                ((Number) m.getOrDefault("posZ2", 0)).intValue(),
                ((Number) m.getOrDefault("x", 40)).intValue(),
                ((Number) m.getOrDefault("y", 40)).intValue()
        );
    }
}
