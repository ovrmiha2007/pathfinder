package com.lecternscanner.client.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Orthogonal (90°) wire routing with optional node avoidance.
 */
public final class WireRouter {
    private WireRouter() {
    }

    public record Seg(int x1, int y1, int x2, int y2) {
    }

    /**
     * Always builds an orthogonal path (0–2 bends).
     * Never returns a diagonal single segment.
     */
    public static List<LogicEdge.Point> defaultElbow(int x1, int y1, int x2, int y2) {
        List<LogicEdge.Point> pts = new ArrayList<>();
        if (x1 == x2 || y1 == y2) {
            return pts; // already a straight H/V
        }
        // Prefer vertical mid-corridor (two 90° bends)
        int midX = (x1 + x2) / 2;
        // Keep mid away from both ends a bit so ports don't look stuck
        if (Math.abs(midX - x1) < 24) {
            midX = x1 + (x2 > x1 ? 40 : -40);
        }
        pts.add(new LogicEdge.Point(midX, y1));
        pts.add(new LogicEdge.Point(midX, y2));
        return pts;
    }

    public static List<LogicEdge.Point> routeAround(
            int x1, int y1, int x2, int y2,
            List<int[]> obstacles,
            int pad
    ) {
        List<LogicEdge.Point> candidate = defaultElbow(x1, y1, x2, y2);
        if (!hitsAny(poly(x1, y1, candidate, x2, y2), obstacles, pad)) {
            return candidate;
        }

        int baseMid = (x1 + x2) / 2;
        int[] offsets = {60, -60, 120, -120, 180, -180, 260, -260, 340, -340, 420, -420};
        for (int off : offsets) {
            int midX = baseMid + off;
            List<LogicEdge.Point> pts = new ArrayList<>();
            pts.add(new LogicEdge.Point(midX, y1));
            pts.add(new LogicEdge.Point(midX, y2));
            if (!hitsAny(poly(x1, y1, pts, x2, y2), obstacles, pad)) {
                return pts;
            }
        }

        int baseMidY = (y1 + y2) / 2;
        for (int off : offsets) {
            int midY = baseMidY + off;
            List<LogicEdge.Point> pts = new ArrayList<>();
            pts.add(new LogicEdge.Point(x1, midY));
            pts.add(new LogicEdge.Point(x2, midY));
            if (!hitsAny(poly(x1, y1, pts, x2, y2), obstacles, pad)) {
                return pts;
            }
        }

        // U-shaped detour: go right of both, then down/up, then left
        int farX = Math.max(x1, x2) + 80;
        for (int extra = 0; extra <= 320; extra += 40) {
            int fx = farX + extra;
            List<LogicEdge.Point> pts = new ArrayList<>();
            pts.add(new LogicEdge.Point(fx, y1));
            pts.add(new LogicEdge.Point(fx, y2));
            if (!hitsAny(poly(x1, y1, pts, x2, y2), obstacles, pad)) {
                return pts;
            }
        }
        int leftX = Math.min(x1, x2) - 80;
        for (int extra = 0; extra <= 320; extra += 40) {
            int fx = leftX - extra;
            List<LogicEdge.Point> pts = new ArrayList<>();
            pts.add(new LogicEdge.Point(fx, y1));
            pts.add(new LogicEdge.Point(fx, y2));
            if (!hitsAny(poly(x1, y1, pts, x2, y2), obstacles, pad)) {
                return pts;
            }
        }

        return candidate;
    }

    public static List<Seg> poly(int x1, int y1, List<LogicEdge.Point> mid, int x2, int y2) {
        List<Seg> segs = new ArrayList<>();
        int px = x1;
        int py = y1;
        if (mid != null) {
            for (LogicEdge.Point p : mid) {
                // split accidental diagonals into L
                if (p.x != px && p.y != py) {
                    segs.add(new Seg(px, py, p.x, py));
                    px = p.x;
                }
                segs.add(new Seg(px, py, p.x, p.y));
                px = p.x;
                py = p.y;
            }
        }
        if (x2 != px && y2 != py) {
            segs.add(new Seg(px, py, x2, py));
            px = x2;
        }
        segs.add(new Seg(px, py, x2, y2));
        return segs;
    }

    /** Ensure path is only H/V segments; returns waypoints (not including ends). */
    public static List<LogicEdge.Point> orthogonalize(int x1, int y1, List<LogicEdge.Point> mid, int x2, int y2) {
        List<LogicEdge.Point> targets = new ArrayList<>();
        if (mid != null) {
            targets.addAll(mid);
        }
        targets.add(new LogicEdge.Point(x2, y2));

        List<LogicEdge.Point> pts = new ArrayList<>();
        int px = x1;
        int py = y1;
        for (int i = 0; i < targets.size(); i++) {
            LogicEdge.Point t = targets.get(i);
            boolean last = i == targets.size() - 1;
            if (t.x != px && t.y != py) {
                LogicEdge.Point corner = new LogicEdge.Point(t.x, py);
                if (!(corner.x == x1 && corner.y == y1) && !(corner.x == x2 && corner.y == y2)) {
                    pts.add(corner);
                }
            }
            if (!last) {
                pts.add(new LogicEdge.Point(t.x, t.y));
            }
            px = t.x;
            py = t.y;
        }
        return dedupe(pts, x1, y1, x2, y2);
    }

    private static List<LogicEdge.Point> dedupe(List<LogicEdge.Point> pts, int x1, int y1, int x2, int y2) {
        List<LogicEdge.Point> clean = new ArrayList<>();
        for (LogicEdge.Point p : pts) {
            if (p.x == x1 && p.y == y1) {
                continue;
            }
            if (p.x == x2 && p.y == y2) {
                continue;
            }
            if (!clean.isEmpty()) {
                LogicEdge.Point prev = clean.get(clean.size() - 1);
                if (prev.x == p.x && prev.y == p.y) {
                    continue;
                }
            }
            clean.add(p);
        }
        return clean;
    }

    private static boolean hitsAny(List<Seg> segs, List<int[]> obstacles, int pad) {
        if (obstacles == null || obstacles.isEmpty()) {
            return false;
        }
        for (Seg s : segs) {
            for (int[] o : obstacles) {
                if (segHitsRect(s.x1, s.y1, s.x2, s.y2, o[0] - pad, o[1] - pad, o[2] + pad * 2, o[3] + pad * 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segHitsRect(int x1, int y1, int x2, int y2, int rx, int ry, int rw, int rh) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1)) / 4 + 1;
        for (int i = 1; i < steps; i++) {
            float t = i / (float) steps;
            int x = Math.round(x1 + (x2 - x1) * t);
            int y = Math.round(y1 + (y2 - y1) * t);
            if (x >= rx && x <= rx + rw && y >= ry && y <= ry + rh) {
                return true;
            }
        }
        return false;
    }

    public static void separate(LogicNode moving, LogicNode other, int w, int h, int gap) {
        int ax2 = moving.x + w;
        int ay2 = moving.y + h;
        int bx2 = other.x + w;
        int by2 = other.y + h;
        if (moving.x >= bx2 + gap || ax2 + gap <= other.x || moving.y >= by2 + gap || ay2 + gap <= other.y) {
            return;
        }
        int pushL = ax2 - other.x + gap;
        int pushR = bx2 - moving.x + gap;
        int pushU = ay2 - other.y + gap;
        int pushD = by2 - moving.y + gap;
        int min = Math.min(Math.min(pushL, pushR), Math.min(pushU, pushD));
        if (min == pushL) {
            moving.x = other.x - w - gap;
        } else if (min == pushR) {
            moving.x = bx2 + gap;
        } else if (min == pushU) {
            moving.y = other.y - h - gap;
        } else {
            moving.y = by2 + gap;
        }
    }

    public static void insertBend(LogicEdge edge, int x1, int y1, int x2, int y2, int wx, int wy) {
        List<LogicEdge.Point> chain = new ArrayList<>();
        chain.add(new LogicEdge.Point(x1, y1));
        chain.addAll(edge.waypoints);
        chain.add(new LogicEdge.Point(x2, y2));
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < chain.size() - 1; i++) {
            LogicEdge.Point a = chain.get(i);
            LogicEdge.Point b = chain.get(i + 1);
            double d = distToSeg(wx, wy, a.x, a.y, b.x, b.y);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        LogicEdge.Point a = chain.get(best);
        LogicEdge.Point b = chain.get(best + 1);
        LogicEdge.Point bend;
        if (a.y == b.y) {
            bend = new LogicEdge.Point(wx, a.y);
        } else if (a.x == b.x) {
            bend = new LogicEdge.Point(a.x, wy);
        } else {
            bend = new LogicEdge.Point(wx, a.y);
        }
        edge.waypoints.clear();
        for (int i = 1; i < chain.size() - 1; i++) {
            edge.waypoints.add(chain.get(i));
        }
        edge.waypoints.add(Math.min(best, edge.waypoints.size()), bend);
        List<LogicEdge.Point> ortho = orthogonalize(x1, y1, edge.waypoints, x2, y2);
        edge.waypoints.clear();
        edge.waypoints.addAll(ortho);
    }

    private static double distToSeg(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0 && dy == 0) {
            return Math.hypot(px - x1, py - y1);
        }
        double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)));
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }
}
