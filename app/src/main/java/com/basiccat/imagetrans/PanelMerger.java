package com.basiccat.imagetrans;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * Groups recognized text lines into the blocks that are translated and
 * rendered together. Used for comics, with the server API and locally.
 *
 * Lines are clustered first, by panel (a speech balloon or a paragraph
 * area), so that lines of different panels never join:
 *
 * - the panels ImageTrans detected or the user drew, sent along with the
 *   lines; a line goes to the smallest panel holding it;
 * - without them, an estimate from the lines: k-means with a gap penalty.
 *
 * Inside a panel, horizontal text merges left to right first (fragments on
 * one line) and then top to bottom (lines into paragraphs); vertical text
 * merges top to bottom first (fragments of a column) and then right to
 * left (columns). The blocks follow the reading order of the lines.
 */
final class PanelMerger {
    private static final String TAG = "PanelMerger";
    private static final String NEWLINE = System.lineSeparator();
    /** A line joins a column when this share of it lies in it. */
    private static final double MIN_COLUMN_OVERLAP = 0.5;
    /** Default gaps between two lines of one block, for callers that pass 0. */
    private static final double DEFAULT_X_GAP = 15;
    private static final double DEFAULT_Y_GAP = 15;

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
        if (xGap <= 0) xGap = DEFAULT_X_GAP;
        if (yGap <= 0) yGap = DEFAULT_Y_GAP;
        logLines("recognized", items);
        Layout layout = Layout.ofLines(items);
        List<Box> blocks = merge(items, layout, sourceLang, xGap, yGap, panelsOut);
        logPanels(layout, items);
        logLines("merged", blocks);
        return blocks;
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
        if (xGap <= 0) xGap = DEFAULT_X_GAP;
        if (yGap <= 0) yGap = DEFAULT_Y_GAP;
        logLines("recognized", items);
        Layout layout = panelRects == null || panelRects.isEmpty()
                ? Layout.ofLines(items) : Layout.ofPanels(items, panelRects);
        List<Box> blocks = merge(items, layout, sourceLang, xGap, yGap, null);
        logPanels(layout, items);
        logLines("merged", blocks);
        return blocks;
    }

    private static List<Box> merge(List<Box> items, Layout layout, String sourceLang,
                                   double xGap, double yGap, org.json.JSONArray panelsOut) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        if (xGap <= 0) xGap = DEFAULT_X_GAP;
        if (yGap <= 0) yGap = DEFAULT_Y_GAP;
        final boolean rightToLeft = "ar".equals(sourceLang);

        // Vertical text: most lines are much taller than wide (a column
        // fragment is tall and narrow, a line of prose only slightly so).
        int tall = 0;
        for (int i = 0; i < items.size(); i++) {
            Box item = items.get(i);
            if (item.height > item.width * TALL_RATIO) tall++;
        }
        final boolean vertical = tall > items.size() / 2;

        // Like the desktop merge, panel by panel (clusters of neighboring
        // lines): lines of different panels never join. Without panels the
        // estimated ones are used, and reported to the server.
        if (panelsOut != null && layout instanceof Estimated) {
            ((Estimated) layout).toJson(items, panelsOut);
        }
        // Blocks keep their panel's reading order (the order the lines are
        // stored in), with the lines of the other panels filled in after
        // them: see Layout.partition().
        // The blocks follow the .itp's order where its lines (boxes) are in
        // reading order: the order the panels are first reached. Otherwise
        // they are sorted by position, as the merge produces them.
        List<Box> blocks = new ArrayList<>();
        for (List<Box> panel : layout.partition(items, vertical)) {
            List<Box> ofPanel = mergePanel(panel, sourceLang, vertical, rightToLeft, xGap, yGap);
            if (!inReadingOrder(panel)) {
                Collections.sort(ofPanel, new Comparator<Box>() {
                    @Override
                    public int compare(Box a, Box b) {
                        return vertical ? Double.compare(b.right(), a.right())
                                : Double.compare(a.top(), b.top());
                    }
                });
            }
            blocks.addAll(ofPanel);
        }
        return blocks;
    }

    /**
     * One panel's lines into text blocks.
     *
     * Horizontal text: first the fragments on one line are joined from left
     * to right (by x), then the resulting lines into paragraphs from top to
     * bottom (by y); two fragments are on one line when their rows overlap
     * most of the smaller one's height (the lines of a paragraph may overlap
     * a little, so this must not be slack). Vertical text: first the columns
     * (fragments one below the other, from top to bottom), then the columns
     * from right to left.
     */
    private static List<Box> mergePanel(List<Box> panel, String sourceLang, boolean vertical,
                                        boolean rightToLeft, double xGap, double yGap) {
        // The lines (".itp" boxes) of a panel are grouped row by row, in the
        // order they are stored: line fragments, then the next line.
        List<List<Box>> rows = new ArrayList<>();
        boolean[] used = new boolean[panel.size()];
        for (int i = 0; i < panel.size(); i++) {
            if (used[i]) continue;
            List<Box> row = new ArrayList<>();
            row.add(panel.get(i));
            used[i] = true;
            for (int j = i; j < panel.size(); j++) {
                if (used[j]) continue;
                boolean part = false;
                for (Box member : row) part |= isPartOfRow(panel.get(j), member, vertical, xGap, yGap);
                if (part) {
                    row.add(panel.get(j));
                    used[j] = true;
                }
            }
            Collections.sort(row, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    if (vertical) {
                        int byColumn = Double.compare(b.right(), a.right());
                        return byColumn != 0 ? byColumn : Double.compare(a.top(), b.top());
                    }
                    if (rightToLeft) return Double.compare(b.left(), a.left());
                    return Double.compare(a.left(), b.left());
                }
            });
            rows.add(row);
        }

        // The second phase: rows into blocks, in reading order. Horizontal
        // text reads top to bottom, vertical text each column top to bottom
        // and the columns right to left.
        if (!vertical) {
            Collections.sort(rows, new Comparator<List<Box>>() {
                @Override
                public int compare(List<Box> a, List<Box> b) {
                    return Double.compare(top(a), top(b));
                }
            });
        } else {
            Collections.sort(rows, new Comparator<List<Box>>() {
                @Override
                public int compare(List<Box> a, List<Box> b) {
                    int byColumn = Double.compare(right(a), right(b));
                    return byColumn != 0 ? -byColumn : Double.compare(top(a), top(b));
                }
            });
        }
        // A row continues the block it overlaps most in its last row, which
        // keeps a column's lines together; a row beside another block is not
        // pulled into that block only because they nearly touch.
        List<List<Box>> groups = new ArrayList<>();
        List<int[]> lastRow = new ArrayList<>();
        for (List<Box> row : rows) {
            int bestStart = -1;
            double bestOverlap = 0;
            for (int g = 0; g < groups.size(); g++) {
                int[] last = lastRow.get(g);
                List<Box> above = groups.get(g).subList(last[0], last[1]);
                double overlap = vertical ? columnOverlap(above, row) : horizontalOverlap(above, row);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    bestStart = g;
                }
            }
            if (bestStart >= 0 && bestOverlap >= CONTINUE_OVERLAP) {
                List<Box> group = groups.get(bestStart);
                int from = group.size();
                group.addAll(row);
                lastRow.set(bestStart, new int[]{from, group.size()});
            } else {
                List<Box> group = new ArrayList<>(row);
                groups.add(group);
                lastRow.add(new int[]{0, group.size()});
            }
        }

        List<Box> blocks = new ArrayList<>();
        for (List<Box> group : groups) {
            Box union = groupUnion(group);
            union.source = joinText(group, sourceLang);
            blocks.add(union);
        }
        return blocks;
    }

    /** Share of the narrower row that lies above (or below) the other. */
    private static double horizontalOverlap(List<Box> above, List<Box> below) {
        double overlap = Math.min(right(above), right(below)) - Math.max(left(above), left(below));
        double narrower = Math.min(width(above), width(below));
        return narrower <= 0 ? 0 : Math.max(0, overlap) / narrower;
    }

    /** Share of the narrower column that lies beside the other. */
    private static double columnOverlap(List<Box> left, List<Box> right) {
        double overlap = Math.min(bottom(left), bottom(right)) - Math.max(top(left), top(right));
        double shorter = Math.min(height(left), height(right));
        return shorter <= 0 ? 0 : Math.max(0, overlap) / shorter;
    }

    /** Whether a row continues the block whose last row it overlaps enough. */
    private static boolean continues(List<Box> above, List<Box> row, boolean vertical) {
        double overlap = vertical ? columnOverlap(above, row) : horizontalOverlap(above, row);
        double height = vertical ? height(above) + height(row) : width(above) + width(row);
        return overlap >= CONTINUE_OVERLAP || height <= 0;
    }

    /** Whether {@code line} is part of the row that {@code member} is on. */
    private static boolean isPartOfRow(Box line, Box member, boolean vertical,
                                       double xGap, double yGap) {
        if (vertical) {
            // One below the other in the same column.
            double overlap = Math.min(line.right(), member.right())
                    - Math.max(line.left(), member.left());
            double narrower = Math.min(Math.abs(line.width), Math.abs(member.width));
            return narrower > 0 && overlap / narrower >= 0.5
                    && yCloseEnough(line, member, yGap) && xCloseEnough(line, member, xGap);
        }
        return sameLine(member, line, false, xGap, yGap);
    }

    /**
     * The text of a block, line after line. Chinese, Japanese and Thai are
     * written without spaces between words; Korean and the other languages
     * keep them.
     */
    private static String joinText(List<Box> block, String sourceLang) {
        boolean cjk = false;
        for (Box box : block) cjk |= containsCjk(box.source);
        boolean noSpace = "zh".equals(sourceLang) || "ja".equals(sourceLang)
                || "th".equals(sourceLang) || (cjk && !"ko".equals(sourceLang));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < block.size(); i++) {
            if (i > 0 && !noSpace) text.append(' ');
            text.append(block.get(i).source == null ? "" : block.get(i).source.trim());
        }
        return text.toString();
    }

    private static double width(List<Box> row) {
        return right(row) - left(row);
    }

    private static double height(List<Box> row) {
        return bottom(row) - top(row);
    }

    /** The box around a row (or a block) of lines. */
    private static Box bounds(List<Box> row) {
        Box box = row.get(0);
        double left = box.left(), top = box.top(), right = box.right(), bottom = box.bottom();
        for (Box other : row) {
            left = Math.min(left, other.left());
            top = Math.min(top, other.top());
            right = Math.max(right, other.right());
            bottom = Math.max(bottom, other.bottom());
        }
        return new Box(left, top, right - left, bottom - top, "");
    }

    private static double left(List<Box> row) {
        return bounds(row).left();
    }

    private static double right(List<Box> row) {
        return bounds(row).right();
    }

    private static double top(List<Box> row) {
        return bounds(row).top();
    }

    private static double bottom(List<Box> row) {
        return bounds(row).bottom();
    }

    /** Rows must overlap this share to be one line (overlapping only a little is not). */
    private static final double SAME_LINE_OVERLAP = 0.6;
    /** A line this many times taller than wide is vertical text. */
    private static final double TALL_RATIO = 1.5;
    /** A row overlapping this share of the row above continues its block. */
    private static final double CONTINUE_OVERLAP = 0.5;

    /**
     * Whether the lines are already in reading order: each line starts at
     * or below the one before (a few pixels up is still fine), and to its
     * right if it shares the row. Flat coordinate order, where paragraphs
     * interleave row by row, is not.
     */
    private static boolean inReadingOrder(List<Box> lines) {
        if (lines.size() < 3) return true;
        for (int i = 1; i < lines.size(); i++) {
            Box before = lines.get(i - 1);
            Box after = lines.get(i);
            double row = Math.min(Math.abs(before.height), Math.abs(after.height)) * 0.5;
            if (after.top() > before.top() + row) continue;
            if (after.top() < before.top() - row) return false;
            if (after.left() < before.left()) return false;
        }
        return true;
    }

    /** Whether two boxes are side by side on one line (horizontal text). */
    private static boolean sameLine(Box a, Box b, boolean vertical, double xGap, double yGap) {
        if (vertical) {
            return oneBelow(a, b, xGap, yGap) || oneBelow(b, a, xGap, yGap);
        }
        double smaller = Math.min(Math.abs(a.height), Math.abs(b.height));
        double overlap = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
        return smaller > 0 && overlap >= SAME_LINE_OVERLAP * smaller
                && sideBySide(a, b, xGap);
    }

    /**
     * Whether two pieces on one line meet from the left to the right (they
     * overlap or the gap is a few pixels). Below each other on the page
     * (which may happen when the OCR boxes overlap) they do not.
     */
    private static boolean sideBySide(Box a, Box b, double xGap) {
        double gap = Math.max(0, Math.max(a.left(), b.left()) - Math.min(a.right(), b.right()));
        return gap <= xGap;
    }

    /**
     * The vertical columns of a panel: in the boxes' order, each fragment is
     * appended to the column it lies in (sideways) or continues (directly
     * below it), the first one that fits.
     */
    private static List<List<Box>> columns(List<Box> order) {
        List<List<Box>> columns = new ArrayList<>();
        List<Double> bestScore = new ArrayList<>();
        for (Box box : order) {
            int best = -1;
            double score = MIN_COLUMN_OVERLAP;
            for (int c = 0; c < columns.size(); c++) {
                double overlap = columnOverlap(columns.get(c), box);
                if (overlap > score) {
                    score = overlap;
                    best = c;
                }
            }
            if (best < 0) {
                columns.add(new ArrayList<>(Collections.singletonList(box)));
                bestScore.add(0.0);
            } else {
                columns.get(best).add(box);
            }
        }
        return columns;
    }

    /**
     * How much of a (narrow) line lies in a column, as a share of its width:
     * most of it, and the line is beside the column's lines or right below.
     * The neighbouring column of a narrow line may touch it by a pixel; a
     * column's own fragments overlap it almost completely.
     */
    private static double columnOverlap(List<Box> column, Box line) {
        double overlap = 0;
        for (Box member : column) {
            double contact = Math.min(line.right(), member.right()) - Math.max(line.left(), member.left());
            if (contact <= 0) continue;
            double gap = Math.max(0, Math.max(line.top(), member.top())
                    - Math.min(line.bottom(), member.bottom()));
            if (gap <= Math.abs(member.height) * 0.5) overlap = Math.max(overlap, contact);
        }
        double width = Math.abs(line.width);
        return width <= 0 ? 0 : overlap / width;
    }

    /** Whether {@code below} comes after {@code above} in its column. */
    private static boolean oneBelow(Box below, Box above, double xGap, double yGap) {
        double narrower = Math.min(Math.abs(below.width), Math.abs(above.width));
        double overlap = Math.min(below.right(), above.right()) - Math.max(below.left(), above.left());
        return narrower > 0 && overlap / narrower >= 0.5
                && yCloseEnough(above, below, yGap) && xCloseEnough(above, below, xGap);
    }

    private static Box groupUnion(List<Box> group) {
        return bounds(group);
    }

    // --- panel layout -----------------------------------------------------

    /** Which lines share a panel. */
    abstract static class Layout {
        /** Whether a line belongs to the panel a group's first line is in. */
        abstract boolean samePanel(Box first, Box box);

        /**
         * The lines grouped by panel (clusters of neighboring lines), each
         * group in reading order: the boxes' own order (ImageTrans numbers
         * the panels and their lines), or, for flat coordinate order, down
         * the page and across. The groups come out in the reading order of
         * their first line.
         *
         * For vertical text a group is a whole column (a column is what a
         * bubble holds): its fragments in a row are joined by 列 first, so a
         * column is never torn across two bubbles.
         */
        List<List<Box>> partition(List<Box> items, boolean vertical) {
            final List<Box> order = new ArrayList<>(items);
            if (!inReadingOrder(items)) {
                Collections.sort(order, new Comparator<Box>() {
                    @Override
                    public int compare(Box a, Box b) {
                        if (Math.abs(a.top() - b.top()) < Math.min(a.height, b.height) * 0.5) {
                            return Double.compare(a.left(), b.left());
                        }
                        return Double.compare(a.top(), b.top());
                    }
                });
            }
            List<List<Box>> units = new ArrayList<>();
            if (vertical) {
                units.addAll(columns(order));
            } else {
                for (Box box : order) {
                    units.add(new ArrayList<>(Collections.singletonList(box)));
                }
            }
            List<List<Box>> groups = new ArrayList<>();
            for (List<Box> unit : units) {
                int group = -1;
                for (int g = 0; g < groups.size(); g++) {
                    if (sameUnit(groups.get(g).get(0), unit, vertical)) {
                        group = g;
                        break;
                    }
                }
                if (group < 0) {
                    groups.add(new ArrayList<>(unit));
                } else {
                    groups.get(group).addAll(unit);
                }
            }
            return groups;
        }

        /**
         * Whether a unit joins the group of {@code first}: the same panel
         * for the first line of a whole column, otherwise its first line.
         */
        private boolean sameUnit(Box first, List<Box> unit, boolean vertical) {
            return samePanel(first, unit.get(0));
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
        private final List<Box> panels;
        private final IdentityHashMap<Box, Integer> panelOf = new IdentityHashMap<>();

        Detected(List<Box> items, List<Box> panelRects) {
            panels = new ArrayList<>(panelRects);
            // Each line goes to the smallest panel holding it, so nested or
            // overlapping panels keep their own lines apart.
            List<Box> byArea = new ArrayList<>(panels);
            Collections.sort(byArea, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    return Double.compare(area(a), area(b));
                }
            });
            for (Box line : items) {
                for (int i = 0; i < byArea.size(); i++) {
                    Box panel = byArea.get(i);
                    // A line may stick out of its panel a little (the text
                    // runs to the balloon's edge).
                    if (!contains(panel, line, AROUND_LINE)) continue;
                    panelOf.put(line, panels.indexOf(panel));
                    break;
                }
            }
        }

        @Override
        boolean samePanel(Box a, Box b) {
            Integer pa = panelOf.get(a);
            Integer pb = panelOf.get(b);
            // A line outside the drawn panels joins a neighbor unless a panel
            // was drawn between them (e.g. a box around the title only).
            if (pa == null || pb == null) {
                return !separated(panels.get(pa == null ? pb : pa), a, b);
            }
            return pa.equals(pb);
        }
    }

    /** Slack in pixels when deciding which panel a line belongs to. */
    private static final double AROUND_LINE = 1;

    private static boolean contains(Box outer, Box inner, double slack) {
        return inner.left() >= outer.left() - slack && inner.top() >= outer.top() - slack
                && inner.right() <= outer.right() + slack
                && inner.bottom() <= outer.bottom() + slack;
    }

    private static double area(Box box) {
        return Math.abs(box.width * box.height);
    }

    /** Does the panel sit between the two lines, rather than around them? */
    private static boolean separated(Box panel, Box a, Box b) {
        return panel.right() <= Math.max(a.left(), b.left()) + AROUND_LINE
                && panel.right() <= Math.min(a.right(), b.right()) + AROUND_LINE
                || panel.left() >= Math.max(a.right(), b.right()) - AROUND_LINE
                && panel.left() >= Math.min(a.left(), b.left()) - AROUND_LINE
                || panel.bottom() <= Math.max(a.top(), b.top()) + AROUND_LINE
                && panel.bottom() <= Math.min(a.bottom(), b.bottom()) + AROUND_LINE
                || panel.top() >= Math.max(a.bottom(), b.bottom()) - AROUND_LINE
                && panel.top() >= Math.min(a.top(), b.top()) - AROUND_LINE;
    }

    /** Panels estimated from the lines: k-means with a gap penalty. */
    private static final class Estimated extends Layout {
        private final IdentityHashMap<Box, Integer> panelOf = new IdentityHashMap<>();

        Estimated(List<Box> items) {
            int n = items.size();
            int[] labels = n < 4 ? new int[n] : cluster(items);
            for (int i = 0; i < n; i++) panelOf.put(items.get(i), labels[i]);
        }

        @Override
        boolean samePanel(Box a, Box b) {
            Integer pa = panelOf.get(a);
            Integer pb = panelOf.get(b);
            return pa == null || pb == null || pa.equals(pb);
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

    // --- logging ----------------------------------------------------------

    /**
     * Logs the recognized lines and the merged blocks, so a wrong grouping
     * on a device can be traced from the log.
     */
    private static void logLines(String what, List<Box> boxes) {
        StringBuilder text = new StringBuilder(what).append(": ").append(boxes.size()).append(" boxes");
        for (Box box : boxes) {
            text.append(NEWLINE).append(String.format(Locale.US, "  %6.1f,%6.1f %6.1fx%5.1f %s",
                    box.x, box.y, box.width, box.height, box.source));
        }
        Log.d(TAG, text.toString());
    }

    /** Logs which panel each recognized line was put in. */
    private static void logPanels(Layout layout, List<Box> items) {
        StringBuilder text = new StringBuilder("panels:");
        int index = 0;
        for (List<Box> panel : layout.partition(items, false)) {
            text.append(NEWLINE).append("  panel ").append(index++).append(": ");
            for (Box box : panel) {
                text.append('[').append(String.format(Locale.US, "%.0f,%.0f",
                        box.x, box.y)).append("] ");
            }
        }
        Log.d(TAG, text.toString());
    }

    // --- geometry ---------------------------------------------------------

    private static boolean xCloseEnough(Box a, Box b, double xGap) {
        if (a.right() < b.left()) return b.left() - a.right() <= xGap;
        if (b.right() < a.left()) return a.left() - b.right() <= xGap;
        return true;
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
