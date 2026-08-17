package com.lecternscanner.client.logic;

/**
 * Palette node kinds for the visual logic graph.
 */
public enum NodeKind {
    // —— flow ——
    START("Старт", Category.FLOW, 0xFF2A9D8F),
    AREA("Зона", Category.FLOW, 0xFF5B8C5A),
    CHEAT("Чит", Category.FLOW, 0xFFE63946),
    PARALLEL("Паралельно", Category.FLOW, 0xFF9B5DE5),
    IF("Якщо", Category.FLOW, 0xFF3D5A80),
    END("Кінець", Category.FLOW, 0xFFB00020),

    // —— conditions ——
    HAS_ITEM("Є предмет?", Category.COND, 0xFF457B9D),
    HAS_NEAR("Блок поруч?", Category.COND, 0xFF1D3557),
    IN_RADIUS("У радіусі", Category.COND, 0xFF5C4D7A),

    // —— search / actions ——
    FIND_BLOCK("Пошук блоку", Category.ACTION, 0xFF2A6F6F),
    MINE("Добути", Category.ACTION, 0xFFBC6C25),
    PICKUP("Підібрати", Category.ACTION, 0xFF52796F),
    CRAFT("Скрафтити", Category.ACTION, 0xFFD4A373),
    PLACE("Поставити", Category.ACTION, 0xFF7B2D8E),
    SMELT("Переплавити", Category.ACTION, 0xFF9B2226),
    TAKE_FROM("Взяти з", Category.ACTION, 0xFF4A6FA5),
    GOTO("Йти до", Category.ACTION, 0xFF2D6A4F),
    GOTO_POS("Йти в", Category.ACTION, 0xFF40916C),
    FOLLOW("Переслідувати", Category.ACTION, 0xFFE76F51),
    SURVEY("Обстежити", Category.ACTION, 0xFF457B9D);

    public enum Category { FLOW, COND, ACTION }

    public final String label;
    public final Category category;
    public final int color;

    NodeKind(String label, Category category, int color) {
        this.label = label;
        this.category = category;
        this.color = color;
    }

    /** Legacy names from older saves. */
    public static NodeKind parse(String name) {
        if (name == null) {
            return START;
        }
        return switch (name) {
            case "ELSE", "OPTIONAL", "REQUIRED" -> START;
            case "FIND" -> FIND_BLOCK;
            case "ZONE", "RADIUS_ZONE" -> AREA;
            case "XRAY", "VISION" -> CHEAT;
            case "PURSUE", "FOLLOW_ENTITY", "CHASE" -> FOLLOW;
            case "WALK_TO", "GO_TO_COORDS", "GOTO_XYZ", "GOTO_COORDS" -> GOTO_POS;
            case "EXPLORE", "PATROL", "SCOUT" -> SURVEY;
            case "PAR", "BG", "BACKGROUND" -> PARALLEL;
            default -> {
                try {
                    yield NodeKind.valueOf(name);
                } catch (Exception e) {
                    yield START;
                }
            }
        };
    }
}
