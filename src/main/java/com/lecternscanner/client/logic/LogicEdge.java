package com.lecternscanner.client.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wire between two nodes, with optional bend points (draw.io-style). */
public final class LogicEdge {
    public enum Port {
        OUT,
        TRUE,
        FALSE,
        MAYBE
    }

    /** One bend / control point in world coordinates. */
    public static final class Point {
        public int x;
        public int y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public Point copy() {
            return new Point(x, y);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("x", x);
            m.put("y", y);
            return m;
        }

        public static Point fromMap(Map<String, Object> m) {
            return new Point(
                    ((Number) m.getOrDefault("x", 0)).intValue(),
                    ((Number) m.getOrDefault("y", 0)).intValue()
            );
        }
    }

    public final String fromId;
    public final String toId;
    public Port port;
    /** Intermediate bend points between from-port and to-port (world space). */
    public final List<Point> waypoints = new ArrayList<>();

    public LogicEdge(String fromId, String toId, Port port) {
        this.fromId = fromId;
        this.toId = toId;
        this.port = port == null ? Port.OUT : port;
    }

    public LogicEdge copy() {
        LogicEdge e = new LogicEdge(fromId, toId, port);
        for (Point p : waypoints) {
            e.waypoints.add(p.copy());
        }
        return e;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", fromId);
        m.put("to", toId);
        m.put("port", port.name());
        if (!waypoints.isEmpty()) {
            List<Map<String, Object>> pts = new ArrayList<>();
            for (Point p : waypoints) {
                pts.add(p.toMap());
            }
            m.put("waypoints", pts);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    public static LogicEdge fromMap(Map<String, Object> m) {
        Port p = Port.OUT;
        try {
            p = Port.valueOf(String.valueOf(m.getOrDefault("port", "OUT")));
        } catch (Exception ignored) {
        }
        LogicEdge e = new LogicEdge(String.valueOf(m.get("from")), String.valueOf(m.get("to")), p);
        Object wp = m.get("waypoints");
        if (wp instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> raw) {
                    e.waypoints.add(Point.fromMap((Map<String, Object>) raw));
                }
            }
        }
        return e;
    }
}
