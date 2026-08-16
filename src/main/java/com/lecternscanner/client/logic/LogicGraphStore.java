package com.lecternscanner.client.logic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.lecternscanner.LecternScannerMod;

import net.minecraft.client.Minecraft;

/**
 * Current working graph + named schemes under {@code .minecraft/pathfinder/schemes/}.
 */
public final class LogicGraphStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern SAFE_NAME = Pattern.compile("[^a-zA-Z0-9._\\-а-яА-ЯёЁіІїЇєЄґҐ ]+");

    private static LogicGraph current = LogicGraph.blank();
    private static boolean loaded;
    /** Last loaded/saved scheme name (without .json), or empty if untitled. */
    private static String activeScheme = "";

    private LogicGraphStore() {
    }

    public static LogicGraph current() {
        ensureLoaded();
        return current;
    }

    public static void setCurrent(LogicGraph g) {
        current = g == null ? LogicGraph.blank() : g;
        loaded = true;
    }

    public static String activeScheme() {
        return activeScheme == null ? "" : activeScheme;
    }

    public static void setActiveScheme(String name) {
        activeScheme = name == null ? "" : name.trim();
        if (current != null && !activeScheme.isEmpty()) {
            current.name = activeScheme;
        }
    }

    public static Path gameDir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) {
            return null;
        }
        return mc.gameDirectory.toPath();
    }

    /** Autosave / last-edited working copy. */
    public static Path currentFile() {
        Path root = gameDir();
        return root == null ? null : root.resolve("pathfinder").resolve("current.json");
    }

    /** Legacy single-file from older versions. */
    public static Path legacyFile() {
        Path root = gameDir();
        return root == null ? null : root.resolve("lectern_logic.json");
    }

    public static Path schemesDir() {
        Path root = gameDir();
        return root == null ? null : root.resolve("pathfinder").resolve("schemes");
    }

    public static Path schemeFile(String name) {
        Path dir = schemesDir();
        if (dir == null) {
            return null;
        }
        return dir.resolve(sanitizeName(name) + ".json");
    }

    public static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "scheme";
        }
        String s = SAFE_NAME.matcher(raw.trim()).replaceAll("_");
        s = s.replaceAll("\\s+", "_");
        if (s.length() > 48) {
            s = s.substring(0, 48);
        }
        if (s.isBlank()) {
            return "scheme";
        }
        return s;
    }

    public static void ensureDirs() {
        Path dir = schemesDir();
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path parent = currentFile();
            if (parent != null) {
                Files.createDirectories(parent.getParent());
            }
        } catch (IOException e) {
            LecternScannerMod.LOGGER.warn("Failed to create pathfinder dirs", e);
        }
    }

    public static void save() {
        ensureLoaded();
        ensureDirs();
        Path p = currentFile();
        if (p == null) {
            LecternScannerMod.LOGGER.warn("Skip logic save: Minecraft not ready");
            return;
        }
        writeGraph(p, current);
    }

    /** Save current graph as a named scheme (and keep it as active). */
    public static boolean saveScheme(String name) {
        ensureLoaded();
        ensureDirs();
        String clean = sanitizeName(name);
        Path p = schemeFile(clean);
        if (p == null) {
            return false;
        }
        current.name = clean;
        activeScheme = clean;
        writeGraph(p, current);
        save(); // also update working copy
        return true;
    }

    public static boolean loadScheme(String name) {
        ensureDirs();
        String clean = sanitizeName(name);
        Path p = schemeFile(clean);
        if (p == null || !Files.isRegularFile(p)) {
            return false;
        }
        LogicGraph g = readGraph(p);
        if (g == null) {
            return false;
        }
        if (g.nodes.isEmpty()) {
            g = LogicGraph.blank();
        }
        g.name = clean;
        current = g;
        activeScheme = clean;
        loaded = true;
        save();
        return true;
    }

    public static boolean deleteScheme(String name) {
        Path p = schemeFile(name);
        if (p == null || !Files.isRegularFile(p)) {
            return false;
        }
        try {
            Files.delete(p);
            if (sanitizeName(name).equals(activeScheme)) {
                activeScheme = "";
            }
            return true;
        } catch (IOException e) {
            LecternScannerMod.LOGGER.warn("Failed to delete scheme {}", name, e);
            return false;
        }
    }

    public static boolean schemeExists(String name) {
        Path p = schemeFile(name);
        return p != null && Files.isRegularFile(p);
    }

    public static List<String> listSchemes() {
        ensureDirs();
        Path dir = schemesDir();
        List<String> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                String fn = p.getFileName().toString();
                if (fn.toLowerCase(Locale.ROOT).endsWith(".json")) {
                    out.add(fn.substring(0, fn.length() - 5));
                }
            }
        } catch (IOException e) {
            LecternScannerMod.LOGGER.warn("Failed to list schemes", e);
        }
        out.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
        return out;
    }

    public static void load() {
        Path p = currentFile();
        if (p == null) {
            loaded = false;
            return;
        }
        ensureDirs();
        Path src = null;
        if (Files.isRegularFile(p)) {
            src = p;
        } else if (Files.isRegularFile(legacyFile())) {
            src = legacyFile();
        }
        if (src == null) {
            current = LogicGraph.blank();
            activeScheme = "";
            loaded = true;
            return;
        }
        LogicGraph g = readGraph(src);
        if (g == null || g.nodes.isEmpty()) {
            current = LogicGraph.blank();
            activeScheme = "";
        } else {
            current = g;
            activeScheme = g.name == null || "default".equals(g.name) ? "" : g.name;
        }
        loaded = true;
        // Migrate legacy → new path once
        if (src.equals(legacyFile()) && !Files.isRegularFile(p)) {
            save();
        }
    }

    public static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static void writeGraph(Path path, LogicGraph graph) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(graph.toMap()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LecternScannerMod.LOGGER.warn("Failed to save {}", path, e);
        }
    }

    private static LogicGraph readGraph(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Map<String, Object> map = GSON.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
            return LogicGraph.fromMap(map);
        } catch (Exception e) {
            LecternScannerMod.LOGGER.warn("Failed to load {}", path, e);
            return null;
        }
    }
}
