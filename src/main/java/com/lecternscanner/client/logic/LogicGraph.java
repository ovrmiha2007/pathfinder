package com.lecternscanner.client.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LogicGraph {
    public String name = "default";
    public final List<LogicNode> nodes = new ArrayList<>();
    public final List<LogicEdge> edges = new ArrayList<>();

    public Optional<LogicNode> find(String id) {
        return nodes.stream().filter(n -> n.id.equals(id)).findFirst();
    }

    public Optional<LogicNode> start() {
        return nodes.stream().filter(n -> n.kind == NodeKind.START).findFirst();
    }

    public List<LogicEdge> outs(String fromId) {
        List<LogicEdge> list = new ArrayList<>();
        for (LogicEdge e : edges) {
            if (e.fromId.equals(fromId)) {
                list.add(e);
            }
        }
        return list;
    }

    public Optional<LogicEdge> out(String fromId, LogicEdge.Port port) {
        for (LogicEdge e : edges) {
            if (e.fromId.equals(fromId) && e.port == port) {
                return Optional.of(e);
            }
        }
        // fallback: any OUT
        if (port == LogicEdge.Port.OUT) {
            for (LogicEdge e : edges) {
                if (e.fromId.equals(fromId)) {
                    return Optional.of(e);
                }
            }
        }
        return Optional.empty();
    }

    public void connect(String from, String to, LogicEdge.Port port) {
        edges.removeIf(e -> e.fromId.equals(from) && e.port == port);
        if (from.equals(to)) {
            return;
        }
        LogicEdge edge = new LogicEdge(from, to, port);
        LogicNode a = find(from).orElse(null);
        LogicNode b = find(to).orElse(null);
        if (a != null && b != null) {
            int[] out = portAnchor(a, port, true);
            int[] in = portAnchor(b, port, false);
            List<int[]> obstacles = new ArrayList<>();
            for (LogicNode n : nodes) {
                if (n.id.equals(from) || n.id.equals(to)) {
                    continue;
                }
                obstacles.add(new int[]{n.x, n.y, 128, 52});
            }
            edge.waypoints.addAll(WireRouter.routeAround(out[0], out[1], in[0], in[1], obstacles, 6));
        }
        edges.add(edge);
    }

    /** World-space port position on a node. */
    public static int[] portAnchor(LogicNode n, LogicEdge.Port port, boolean outgoing) {
        if (!outgoing) {
            return new int[]{n.x, n.y + 26};
        }
        int y = switch (port) {
            case TRUE -> n.y + 12;
            case FALSE -> n.y + 40;
            default -> n.y + 26;
        };
        return new int[]{n.x + 128, y};
    }

    public boolean removeEdge(LogicEdge edge) {
        return edges.remove(edge);
    }

    public void removeEdgeBetween(String from, String to) {
        edges.removeIf(e -> e.fromId.equals(from) && e.toId.equals(to));
    }

    public LogicNode add(NodeKind kind, int x, int y) {
        LogicNode n = new LogicNode(kind, x, y);
        nodes.add(n);
        return n;
    }

    public void remove(String id) {
        nodes.removeIf(n -> n.id.equals(id));
        edges.removeIf(e -> e.fromId.equals(id) || e.toId.equals(id));
    }

    /** Deep copy via serialization (for undo). */
    public LogicGraph copy() {
        return fromMap(toMap());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        List<Map<String, Object>> ns = new ArrayList<>();
        for (LogicNode n : nodes) {
            ns.add(n.toMap());
        }
        m.put("nodes", ns);
        List<Map<String, Object>> es = new ArrayList<>();
        for (LogicEdge e : edges) {
            es.add(e.toMap());
        }
        m.put("edges", es);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static LogicGraph fromMap(Map<String, Object> m) {
        LogicGraph g = new LogicGraph();
        g.name = String.valueOf(m.getOrDefault("name", "default"));
        Object nodesObj = m.get("nodes");
        if (nodesObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> raw) {
                    LogicNode n = LogicNode.fromMap((Map<String, Object>) raw);
                    if (n != null) {
                        g.nodes.add(n);
                    }
                }
            }
        }
        Object edgesObj = m.get("edges");
        if (edgesObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> raw) {
                    LogicEdge e = LogicEdge.fromMap((Map<String, Object>) raw);
                    if (g.find(e.fromId).isPresent() && g.find(e.toId).isPresent()) {
                        g.edges.add(e);
                    }
                }
            }
        }
        return g;
    }

    /** Empty graph with a Start node on the canvas (world coords). */
    public static LogicGraph blank() {
        LogicGraph g = new LogicGraph();
        g.add(NodeKind.START, 40, 40);
        return g;
    }
}
