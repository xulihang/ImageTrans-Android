package com.basiccat.imagetrans;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Groups recognized text lines into the blocks that are translated and
 * rendered together ("panels"). Used by the ImageTrans server API for comics.
 *
 * A line joins a neighboring line in the same panel (they overlap sideways
 * and are less than one line apart vertically), but never crosses a panel
 * border. Borders come from either source:
 *
 * - the panels the user drew or confirmed in ImageTrans and sent along
 *   ("detected panels"); a line belongs to the smallest panel containing
 *   it, and lines of different panels are kept apart;
 * - without them, an estimate from the lines alone: k-means with a gap
 *   penalty groups lines that sit close, which keeps the lines of one
 *   balloon together and those of two balloons apart.
 */
final class PanelMerger {

    private PanelMerger() {
    }

    /**
     * Without panel borders: they are estimated from the lines and filled
     * into {@code panelsOut} (plain JSON objects with X, Y, width and
     * height), so a server can apply the same grouping.
     */
    static List<Box> merge(List<Box> items, String sourceLang, double xGap, double yGap,
                           org.json.JSONArray panelsOut) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        if (xGap <= 0) xGap = 15;
        if (yGap <= 0) yGap = 15;
        return merge(items, Layout.ofLines(items), sourceLang, xGap, yGap, panelsOut);
    }

    /**
     * With panel borders from ImageTrans (drawn by the user or detected by
     * the desktop program).
     *
     * @param sourceLang language code, for line order ("ar" right to left)
     */
    static List<Box> merge(List<Box> items, List<Box> panelRects, String sourceLang,
                           double xGap, double yGap) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        if (xGap <= 0) xGap = 15;
        if (yGap <= 0) yGap = 15;
        Layout layout = panelRects == null || panelRects.isEmpty()
                ? Layout.ofLines(items) : Layout.ofPanels(items, panelRects);
        return merge(items, layout, sourceLang, xGap, yGap, null);
    }

    private static List<Box> merge(List<Box> items, Layout layout, String sourceLang,
                                   double xGap, double yGap, org.json.JSONArray panelsOut) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        if (xGap <= 0) xGap = 15;
        if (yGap <= 0) yGap = 15;
        List<Box> sorted = new ArrayList<>(items);
        Collections.sort(sorted, new Comparator<Box>() {
            @Override
            public int compare(Box a, Box b) {
                if (Math.abs(a.top() - b.top()) < Math.min(a.height, b.height) * 0.5) {
                    return Double.compare(a.left(), b.left());
                }
                return Double.compare(a.top(), b.top());
            }
        });

        int tall = 0;
        for (int i = 0; i < items.size(); i++) {
            Box item = items.get(i);
            if (item.height > item.width) tall++;
        }
        if (tall > items.size() / 2) {
            // Vertical text: keep the desktop merge (columns joined top to
            // bottom); panels are a horizontal-reading feature.
            return TextBoxMerger.merge(items, sourceLang, xGap, yGap);
        }

        if (panelsOut != null && layout instanceof Estimated) {
            ((Estimated) layout).toJson(items, panelsOut);
        }
        final boolean rightToLeft = "ar".equals(sourceLang);

        // Phase 1: text fragments on the same line.
        List<List<Box>> lines = new ArrayList<>();
        boolean[] used = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            if (used[i]) continue;
            List<Box> line = new ArrayList<>();
            line.add(sorted.get(i));
            used[i] = true;
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int j = 0; j < sorted.size(); j++) {
                    if (used[j]) continue;
                    Box candidate = sorted.get(j);
                    for (int k = 0; k < line.size(); k++) {
                        Box member = line.get(k);
                        if (yOverlapRatio(member, candidate) >= 0.15
                                && xCloseEnough(member, candidate, xGap)
                                && layout.samePanel(member, candidate)) {
                            line.add(candidate);
                            used[j] = true;
                            changed = true;
                            break;
                        }
                    }
                }
            }
            if (line.size() == 1 && layout instanceof Detected) {
                // A panel holding one line also keeps the rest of a line the
                // OCR split off, or words just outside it on the same row.
                for (Box part : layout.addParts(sorted, line.get(0))) {
                    int index = sorted.indexOf(part);
                    if (index < 0 || used[index]) continue;
                    used[index] = true;
                    line.add(part);
                }
            }
            Collections.sort(line, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    if (rightToLeft) return Double.compare(b.left(), a.left());
                    return Double.compare(a.left(), b.left());
                }
            });
            lines.add(line);
        }

        // Phase 2: lines into blocks. Blocks that continue a panel go first,
        // so a single-line panel is not swallowed by a neighbour: the longest
        // panel claims its lines before a short one tries to.
        final Layout panels = layout;
        Collections.sort(lines, new Comparator<List<Box>>() {
            @Override
            public int compare(List<Box> a, List<Box> b) {
                return Integer.compare(panels.weight(b), panels.weight(a));
            }
        });
        List<List<Box>> groups = new ArrayList<>();
        boolean[] usedLine = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (usedLine[i]) continue;
            List<Box> group = new ArrayList<>(lines.get(i));
            usedLine[i] = true;
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int j = 0; j < lines.size(); j++) {
                    if (usedLine[j]) continue;
                    List<Box> other = lines.get(j);
                    if (!joins(panels, group, other, yGap)) continue;
                    group.addAll(other);
                    usedLine[j] = true;
                    changed = true;
                }
            }
            Collections.sort(group, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    if (Math.abs(a.top() - b.top()) < Math.min(a.height, b.height) * 0.5) {
                        if (rightToLeft) return Double.compare(b.left(), a.left());
                        return Double.compare(a.left(), b.left());
                    }
                    return Double.compare(a.top(), b.top());
                }
            });
            groups.add(group);
        }

        // Panels in reading order, lines in each in their natural order.
        Collections.sort(groups, new Comparator<List<Box>>() {
            @Override
            public int compare(List<Box> a, List<Box> b) {
                return Integer.compare(panels.order(groupUnion(a)), panels.order(groupUnion(b)));
            }
        });

        List<Box> merged = new ArrayList<>();
        boolean cjk = false;
        for (int i = 0; i < items.size() && !cjk; i++) cjk = containsCjk(items.get(i).source);
        for (List<Box> group : groups) {
            StringBuilder text = new StringBuilder();
            // Chinese, Japanese and Thai are written without spaces between
            // words; Korean and everything else keep them.
            boolean noSpace = "zh".equals(sourceLang) || "ja".equals(sourceLang)
                    || "th".equals(sourceLang) || (cjk && !"ko".equals(sourceLang));
            for (int i = 0; i < group.size(); i++) {
                Box box = group.get(i);
                if (i > 0 && !noSpace) text.append(' ');
                text.append(box.source == null ? "" : box.source.trim());
            }
            Box union = groupUnion(group);
            union.source = text.toString();
            merged.add(union);
        }
        return merged;
    }

    private static boolean joins(final Layout panels, List<Box> group, List<Box> other,
                                 double yGap) {
        for (Box a : group) {
            for (Box b : other) {
                if (xOverlapRatio(a, b) >= 0.15 && yCloseEnough(a, b, yGap)
                        && panels.samePanel(a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Box groupUnion(List<Box> group) {
        double left = Double.MAX_VALUE, top = Double.MAX_VALUE;
        double right = -Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
        for (Box box : group) {
            left = Math.min(left, box.left());
            top = Math.min(top, box.top());
            right = Math.max(right, box.right());
            bottom = Math.max(bottom, box.bottom());
        }
        return new Box(left, top, right - left, bottom - top, "");
    }

    // --- panel layout -----------------------------------------------------

    /** Which lines share a panel, and where their blocks belong in reading order. */
    abstract static class Layout {
        /** Lines in the same panel (transitive within one panel). */
        abstract boolean samePanel(Box a, Box b);

        /** Larger for a longer panel: its blocks are formed first. */
        int weight(List<Box> line) {
            return line.size();
        }

        // Ordering key of a block, smaller is read first.
        abstract int order(Box block);

        /**
         * Lines a single-line panel keeps along with its line: the rest of a
         * line the OCR split off, or words just outside it on the same row.
         * None by default.
         */
        List<Box> addParts(List<Box> all, Box line) {
            return new ArrayList<>();
        }

        static Layout ofPanels(List<Box> items, List<Box> panelRects) {
            return new Detected(items, panelRects);
        }

        static Layout ofLines(List<Box> items) {
            return new Estimated(items);
        }
    }

    /** Panels from ImageTrans: each line belongs to the smallest one holding it. */
    private static final class Detected extends Layout {
        private final List<Box> sortedPanels;
        private final java.util.IdentityHashMap<Box, Integer> panelOf =
                new java.util.IdentityHashMap<>();
        private final List<Integer> weights = new ArrayList<>();

        Detected(List<Box> items, List<Box> panelRects) {
            sortedPanels = new ArrayList<>(panelRects);
            // Reading order: top to bottom, left to right.
            Collections.sort(sortedPanels, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    double tolerance = Math.max(a.height, b.height) * 0.6;
                    if (Math.abs(a.top() - b.top()) > tolerance) {
                        return Double.compare(a.top(), b.top());
                    }
                    return Double.compare(a.left(), b.left());
                }
            });
            // Each line goes to the smallest panel holding it, so nested or
            // overlapping panels keep their own lines apart.
            List<Box> byArea = new ArrayList<>(sortedPanels);
            Collections.sort(byArea, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    return Double.compare(area(a), area(b));
                }
            });
            int[] sizes = new int[sortedPanels.size()];
            for (Box line : items) {
                for (Box panel : byArea) {
                    // A line may stick out of its panel a little (the text
                    // runs to the balloon's edge); halfway to the neighbor is
                    // the border.
                    if (!contains(panel, line, AROUND_LINE)) continue;
                    int index = sortedPanels.indexOf(panel);
                    panelOf.put(line, index);
                    sizes[index]++;
                    break;
                }
            }
            for (int size : sizes) weights.add(size);
        }

        @Override
        int weight(List<Box> line) {
            int best = 0;
            for (Box box : line) {
                Integer index = panelOf.get(box);
                if (index != null) best = Math.max(best, weights.get(index));
            }
            return best;
        }

        @Override
        boolean samePanel(Box a, Box b) {
            Integer pa = panelOf.get(a);
            Integer pb = panelOf.get(b);
            return pa == null || pb == null || pa.equals(pb);
        }

        @Override
        List<Box> addParts(List<Box> all, Box line) {
            List<Box> extra = new ArrayList<>();
            Integer index = panelOf.get(line);
            if (index == null || weights.get(index) != 1) return extra;
            Box panel = sortedPanels.get(index);
            double reach = Math.abs(panel.height) * 2;
            for (Box box : all) {
                if (panelOf.containsKey(box)) continue;
                if (rowOverlap(box, panel) < 0.5) continue;
                boolean rightNext = box.left() >= panel.right() - AROUND_LINE
                        && box.left() - panel.right() <= reach;
                boolean leftNext = box.right() <= panel.left() + AROUND_LINE
                        && panel.left() - box.right() <= reach;
                if (rightNext || leftNext) extra.add(box);
            }
            return extra;
        }

        @Override
        int order(Box block) {
            for (int i = 0; i < sortedPanels.size(); i++) {
                if (contains(sortedPanels.get(i), block, AROUND_BLOCK)) return i;
            }
            // Not in any drawn panel: between the panels it falls between.
            int key = 0;
            for (int i = 0; i < sortedPanels.size(); i++) {
                if (sortedPanels.get(i).top() <= block.top()) key = i + 1;
            }
            return key;
        }
    }

    /** Slack in pixels when deciding which panel a line belongs to. */
    private static final double AROUND_LINE = 1;
    /** Slack when looking up the panel of a finished block. */
    private static final double AROUND_BLOCK = 6;

    private static boolean contains(Box outer, Box inner, double slack) {
        return inner.left() >= outer.left() - slack && inner.top() >= outer.top() - slack
                && inner.right() <= outer.right() + slack
                && inner.bottom() <= outer.bottom() + slack;
    }

    private static double area(Box box) {
        return Math.abs(box.width * box.height);
    }

    /** Share of a box's height that overlaps a panel's rows. */
    private static double rowOverlap(Box box, Box panel) {
        double overlap = Math.min(box.bottom(), panel.bottom()) - Math.max(box.top(), panel.top());
        return Math.abs(box.height) <= 0 ? 0 : Math.max(0, overlap) / Math.abs(box.height);
    }

    /** Panels estimated from the lines: k-means with a gap penalty. */
    private static final class Estimated extends Layout {
        private final java.util.IdentityHashMap<Box, Integer> panelOf =
                new java.util.IdentityHashMap<>();
        private final java.util.HashMap<Integer, Integer> sizes = new java.util.HashMap<>();

        Estimated(List<Box> items) {
            int n = items.size();
            if (n < 4) {
                for (Box item : items) panelOf.put(item, 0);
                return;
            }
            int[] labels = cluster(items);
            for (int i = 0; i < n; i++) {
                panelOf.put(items.get(i), labels[i]);
                Integer count = sizes.get(labels[i]);
                sizes.put(labels[i], count == null ? 1 : count + 1);
            }
        }

        @Override
        boolean samePanel(Box a, Box b) {
            Integer pa = panelOf.get(a);
            Integer pb = panelOf.get(b);
            return pa == null || pb == null || pa.equals(pb);
        }

        @Override
        int weight(List<Box> line) {
            int best = 0;
            for (Box box : line) {
                Integer size = sizes.get(panelOf.get(box));
                if (size != null) best = Math.max(best, size);
            }
            return best;
        }

        @Override
        int order(Box block) {
            return 0;
        }

        /** The estimated panels as plain rectangles, for a server. */
        void toJson(List<Box> lines, org.json.JSONArray out) {
            java.util.TreeMap<Integer, Box> areas = new java.util.TreeMap<>();
            for (Box line : lines) {
                Integer label = panelOf.get(line);
                if (label == null) continue;
                Box area = areas.get(label);
                double left = area == null ? line.left() : Math.min(area.left(), line.left());
                double top = area == null ? line.top() : Math.min(area.top(), line.top());
                double right = area == null ? line.right() : Math.max(area.right(), line.right());
                double bottom = area == null ? line.bottom() : Math.max(area.bottom(), line.bottom());
                areas.put(label, new Box(left, top, right - left, bottom - top, ""));
            }
            try {
                for (Box area : areas.values()) {
                    org.json.JSONObject item = new org.json.JSONObject();
                    item.put("X", area.left());
                    item.put("Y", area.top());
                    item.put("width", area.width);
                    item.put("height", area.height);
                    item.put("class", "");
                    out.put(item);
                }
            } catch (org.json.JSONException e) {
                // Only fixed keys, so this cannot fail; never break a merge.
            }
        }
    }

    // --- k-means with a gap penalty ---------------------------------------

    /** A line this far (in line heights) from its panel's centre is "far". */
    private static final double PENALTY_HEIGHTS = 2.5;
    private static final int MAX_PANELS = 40;
    private static final int RESTARTS = 4;
    private static final int ITERATIONS = 50;

    private static int[] cluster(List<Box> lines) {
        int n = lines.size();
        int k = Math.max(1, Math.min(n / 2, MAX_PANELS));
        float[] px = new float[n];
        float[] py = new float[n];
        double heights = 0;
        for (int i = 0; i < n; i++) {
            Box box = lines.get(i);
            px[i] = (float) box.centerX();
            py[i] = (float) (box.top() + box.height / 2);
            heights += Math.abs(box.height);
        }
        float lineHeight = (float) Math.max(heights / n, 1.0);
        float penalty = (float) PENALTY_HEIGHTS * lineHeight;
        int[] best = null;
        double bestCost = Double.MAX_VALUE;
        for (int restart = 0; restart < RESTARTS; restart++) {
            java.util.Random random = new java.util.Random(restart);
            float[] cx = new float[k];
            float[] cy = new float[k];
            for (int c = 0; c < k; c++) {
                int seed = random.nextInt(n);
                cx[c] = px[seed];
                cy[c] = py[seed];
            }
            int[] labels = new int[n];
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                boolean changed = false;
                for (int i = 0; i < n; i++) {
                    int group = 0;
                    double nearest = Double.MAX_VALUE;
                    for (int c = 0; c < k; c++) {
                        double d = distance(px[i], py[i], cx[c], cy[c], penalty);
                        if (d < nearest) {
                            nearest = d;
                            group = c;
                        }
                    }
                    if (labels[i] != group) {
                        labels[i] = group;
                        changed = true;
                    }
                }
                if (!changed && iteration > 0) break;
                double[] sumX = new double[k];
                double[] sumY = new double[k];
                int[] count = new int[k];
                for (int i = 0; i < n; i++) {
                    sumX[labels[i]] += px[i];
                    sumY[labels[i]] += py[i];
                    count[labels[i]]++;
                }
                for (int c = 0; c < k; c++) {
                    if (count[c] == 0) continue;
                    cx[c] = (float) (sumX[c] / count[c]);
                    cy[c] = (float) (sumY[c] / count[c]);
                }
            }
            double cost = 0;
            boolean[] used = new boolean[k];
            for (int i = 0; i < n; i++) {
                used[labels[i]] = true;
                cost += distance(px[i], py[i], cx[labels[i]], cy[labels[i]], penalty);
            }
            for (boolean u : used) {
                if (!u) cost += penalty;
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = labels.clone();
            }
        }
        return best;
    }

    /**
     * Distance to a centre, plus the penalty once the line is far from it.
     * Under the assumption that the panels hold one line each, this recovers
     * them exactly whatever the start; with several lines per panel, a line
     * is at worst attached to a neighbouring panel, never the other way.
     */
    private static double distance(float x, float y, float cx, float cy, float penalty) {
        double dx = Math.abs(x - cx);
        double dy = Math.abs(y - cy);
        return Math.hypot(dx, dy) + (Math.max(dx, dy) > penalty ? penalty : 0);
    }

    // --- geometry ---------------------------------------------------------

    private static double xOverlapRatio(Box a, Box b) {
        double minWidth = Math.min(Math.abs(a.width), Math.abs(b.width));
        if (minWidth <= 0) return 0;
        double marginA = Math.min(Math.abs(a.width) * 0.25, 20);
        double marginB = Math.min(Math.abs(b.width) * 0.25, 20);
        double overlap = Math.min(a.right() + marginA, b.right() + marginB)
                - Math.max(a.left() - marginA, b.left() - marginB);
        return overlap <= 0 ? 0 : overlap / minWidth;
    }

    private static boolean xCloseEnough(Box a, Box b, double xGap) {
        if (a.right() < b.left()) return b.left() - a.right() <= xGap;
        if (b.right() < a.left()) return a.left() - b.right() <= xGap;
        return true;
    }

    private static double yOverlapRatio(Box a, Box b) {
        double minHeight = Math.min(Math.abs(a.height), Math.abs(b.height));
        if (minHeight <= 0) return 0;
        double marginA = Math.min(Math.abs(a.height) * 0.25, 30);
        double marginB = Math.min(Math.abs(b.height) * 0.25, 30);
        double overlap = Math.min(a.bottom() + marginA, b.bottom() + marginB)
                - Math.max(a.top() - marginA, b.top() - marginB);
        return overlap <= 0 ? 0 : overlap / minHeight;
    }

    private static boolean yCloseEnough(Box a, Box b, double yGap) {
        if (a.bottom() < b.top()) return b.top() - a.bottom() <= yGap;
        if (b.bottom() < a.top()) return a.top() - b.bottom() <= yGap;
        return true;
    }

    private static boolean containsCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // Hangul (U+AC00-D7AF) is absent on purpose: Korean separates
            // words with spaces and keeps them.
            if ((ch >= 0x4E00 && ch <= 0x9FFF)
                    || (ch >= 0x3400 && ch <= 0x4DBF)
                    || (ch >= 0x3040 && ch <= 0x30FF)
                    || (ch >= 0x3000 && ch <= 0x303F)
                    || (ch >= 0xFF00 && ch <= 0xFFEF)) {
                return true;
            }
        }
        return false;
    }
}
