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
 * - without them, the lines are grouped by alignment and proximity (see
 *   cluster()), the rules worked out in experiments/balloon_clustering.
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
        final boolean vertical = verticalText(items);

        // Like the desktop merge, panel by panel (clusters of neighboring
        // lines): lines of different panels never join. Without panels the
        // estimated ones are used, and reported to the server.
        if (panelsOut != null && layout instanceof Estimated) {
            ((Estimated) layout).toJson(items, panelsOut);
        }
        // Panel by panel, the blocks in reading order: the panels in rows from
        // top to bottom, and the panels of a row from the right for vertical
        // text (a manga reads from the right), from the left otherwise (see
        // experiments/balloon_clustering). A panel whose lines are stored in
        // reading order (ImageTrans numbers them when it detects the panels)
        // keeps its own order; otherwise the same rule sorts its blocks.
        List<Box> blocks = new ArrayList<>();
        for (List<Box> panel : layout.partition(items, true)) {
            List<Box> ofPanel = mergePanel(panel, sourceLang, vertical, rightToLeft, xGap, yGap);
            if (!inReadingOrder(panel)) orderBlocks(ofPanel, vertical);
            blocks.addAll(ofPanel);
        }
        // The panels follow the same rule (a panel's start is its first
        // line, which arrives first in the list).
        orderBlocks(blocks, vertical);
        return blocks;
    }

    /**
     * Puts a panel's blocks in reading order: rows from top to bottom, where
     * two are on one row when their heights overlap by at least half the
     * shorter one; the blocks of a row from the right for vertical text, from
     * the left otherwise.
     */
    private static void orderBlocks(List<Box> blocks, boolean vertical) {
        if (blocks.size() < 2) return;
        List<List<Box>> rows = new ArrayList<>();
        List<Box> rowAreas = new ArrayList<>();
        for (Box block : blocks) {
            int row = -1;
            for (int r = 0; r < rows.size(); r++) {
                if (sameRow(block, rowAreas.get(r))) {
                    row = r;
                    break;
                }
            }
            if (row < 0) {
                rows.add(new ArrayList<>(Collections.singletonList(block)));
                rowAreas.add(block);
            } else {
                rows.get(row).add(block);
                rowAreas.set(row, union(rowAreas.get(row), block));
            }
        }
        blocks.clear();
        for (final List<Box> row : rows) {
            // A stable sort keeps the stored order of two blocks that start
            // at the same place.
            Collections.sort(row, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    return vertical ? Double.compare(b.left(), a.left())
                            : Double.compare(a.left(), b.left());
                }
            });
            blocks.addAll(row);
        }
    }

    /** Whether two areas are on one row: their heights overlap by half. */
    private static boolean sameRow(Box a, Box b) {
        double overlap = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
        double shorter = Math.min(Math.abs(a.height), Math.abs(b.height));
        return shorter > 0 && overlap >= 0.5 * shorter;
    }

    private static Box union(Box a, Box b) {
        double left = Math.min(a.left(), b.left());
        double top = Math.min(a.top(), b.top());
        double right = Math.max(a.right(), b.right());
        double bottom = Math.max(a.bottom(), b.bottom());
        return new Box(left, top, right - left, bottom - top, "");
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

    /** Vertical text: most lines are much taller than wide. */
    private static boolean verticalText(List<Box> items) {
        int tall = 0;
        for (int i = 0; i < items.size(); i++) {
            Box item = items.get(i);
            if (item.height > item.width * TALL_RATIO) tall++;
        }
        return tall * 2 > items.size();
    }
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
         * group in the boxes' own order (ImageTrans numbers the panels and
         * their lines), or, for flat coordinate order, down the page and
         * across. The groups come out in the order of their first line.
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
            for (Box box : order) {
                units.add(new ArrayList<>(Collections.singletonList(box)));
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

        /** Whether a line joins the panel of a group's first line. */
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

    /** Panels estimated from the lines. */
    private static final class Estimated extends Layout {
        private final IdentityHashMap<Box, Integer> panelOf = new IdentityHashMap<>();

        Estimated(List<Box> items) {
            int n = items.size();
            int[] labels = cluster(items, verticalText(items));
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

    // --- panels estimated from the lines ----------------------------------
    //
    // Worked out with the pages in experiments/balloon_clustering: two sets
    // of rules, one for text written in lines (horizontal) and one for
    // columns (vertical).

    /** Vertical text: tops within this many pixels are one row. */
    private static final double COLUMN_ROW = 8;
    /** Vertical text: columns of one balloon are at most this far apart. */
    private static final double COLUMN_SIDE = 70;
    /** Vertical text: one column's right edges are this close. */
    private static final double COLUMN_EDGE = 8;
    /** Vertical text: stacked lines of one balloon are this far apart. */
    private static final double COLUMN_BELOW = 40;
    /** Horizontal text: centres or left edges within this share of the height. */
    private static final double LINE_EDGE = 0.3;
    /** Horizontal text: lines further apart than this many heights are apart. */
    private static final double LINE_GAP_HEIGHTS = 1.6;
    /** Horizontal text: ... nor than this many usual line pitches. */
    private static final double LINE_PITCH = 1.7;

    /**
     * Groups the lines into the panels they belong to (speech balloons or
     * paragraphs) from the lines alone, for text that comes without panels.
     */
    private static int[] cluster(List<Box> lines, boolean vertical) {
        int n = lines.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        if (vertical) {
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (columnsTogether(lines, lines.get(i), lines.get(j))) {
                        union(parent, i, j);
                    }
                }
            }
        } else {
            double pitch = pitch(lines);
            // The page's alignment: a pair of lines may be set both ways at
            // once (a left-aligned paragraph's lines may also be centred),
            // and it counts for both.
            for (int way = 0; way < 2; way++) {
                for (int i = 0; i < n; i++) {
                    for (int j = i + 1; j < n; j++) {
                        if (linesTogether(lines, lines.get(i), lines.get(j), way == 0, pitch)) {
                            union(parent, i, j);
                        }
                    }
                }
            }
        }
        int[] labels = new int[n];
        IdentityHashMap<Integer, Integer> ids = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            Integer label = ids.get(root);
            if (label == null) {
                label = ids.size();
                ids.put(root, label);
            }
            labels[i] = label;
        }
        return labels;
    }

    // --- vertical text: columns, top to bottom, right to left ---------------

    /**
     * Two columns belong to one balloon when the gaps in both directions are
     * small: on one row (tops within {@link #COLUMN_ROW}) and at most
     * {@link #COLUMN_SIDE} apart, with no other column between them; or one
     * right below the other in the same column (right edges within
     * {@link #COLUMN_EDGE}, at most {@link #COLUMN_BELOW} apart).
     */
    private static boolean columnsTogether(List<Box> lines, Box a, Box b) {
        double side = Math.max(a.left(), b.left()) - Math.min(a.right(), b.right());
        double below = Math.max(a.top(), b.top()) - Math.min(a.bottom(), b.bottom());
        if (Math.abs(a.top() - b.top()) <= COLUMN_ROW && side <= COLUMN_SIDE) {
            Box left = a.left() <= b.left() ? a : b;
            Box right = left == a ? b : a;
            if (!columnBetween(lines, left, right)) return true;
        }
        return Math.abs(a.right() - b.right()) <= COLUMN_EDGE && below <= COLUMN_BELOW;
    }

    /** Whether another line on their row stands between the two columns. */
    private static boolean columnBetween(List<Box> lines, Box left, Box right) {
        for (Box other : lines) {
            if (other == left || other == right) continue;
            if (Math.abs(other.top() - left.top()) > COLUMN_ROW) continue;
            if (other.left() > left.left() && other.right() < right.right()) return true;
        }
        return false;
    }

    // --- horizontal text: lines in a paragraph or balloon -------------------

    /** The usual distance between neighboring lines: the most common one. */
    private static double pitch(List<Box> lines) {
        List<Double> tops = new ArrayList<>();
        for (Box line : lines) tops.add(line.top());
        Collections.sort(tops);
        List<Long> diffs = new ArrayList<>();
        for (int i = 1; i < tops.size(); i++) {
            double diff = tops.get(i) - tops.get(i - 1);
            if (diff > 0) diffs.add(Math.round(diff / 2));
        }
        // Ties go to the smaller distance (a page may have as many wider
        // gaps as its line pitch).
        long best = 0;
        int bestCount = 0;
        for (Long diff : diffs) {
            int count = Collections.frequency(diffs, diff);
            if (count > bestCount || count == bestCount && (best == 0 || diff < best)) {
                bestCount = count;
                best = diff;
            }
        }
        return best > 0 ? best * 2 : 20;
    }

    /**
     * Two lines belong to one balloon when they are set alike (centred for
     * {@code centred}, otherwise left-aligned), close (no wider than the
     * pitch) and no line of another balloon lies between them.
     */
    private static boolean linesTogether(List<Box> lines, Box a, Box b, boolean centred,
                                         double pitch) {
        double height = Math.max(Math.abs(a.height), Math.abs(b.height));
        if (Math.abs(a.top() - b.top()) > LINE_GAP_HEIGHTS * pitch) return false;
        if (b.top() - a.top() > LINE_PITCH * pitch) return false;
        double edge = LINE_EDGE * height;
        boolean setAlike = centred
                ? Math.abs(a.centerX() - b.centerX()) <= edge
                : Math.abs(a.left() - b.left()) <= edge;
        if (!setAlike) return false;
        for (Box middle : lines) {
            if (middle == a || middle == b) continue;
            if (liesBetween(a, b, middle, pitch)) return false;
        }
        return true;
    }

    /**
     * Whether a line of another balloon lies between the two: it starts below
     * one and above the other, and runs across their column (most of their
     * shared width, with a gap wider than the pitch) or right beside them
     * (much of its own width).
     */
    private static boolean liesBetween(Box a, Box b, Box middle, double pitch) {
        Box top = a.top() <= b.top() ? a : b;
        Box bottom = top == a ? b : a;
        if (!(middle.top() > top.top() + Math.abs(top.height) * 0.5
                && middle.top() < bottom.top() - Math.abs(bottom.height) * 0.5)) {
            return false;
        }
        double left = Math.max(a.left(), b.left());
        double right = Math.min(a.right(), b.right());
        double shared = right - left;
        double overlap = Math.min(right, middle.right()) - Math.max(left, middle.left());
        if (shared > 0 && overlap >= 0.5 * shared) {
            return middle.top() - top.top() > LINE_PITCH * pitch;
        }
        for (Box line : new Box[]{a, b}) {
            double own = Math.min(line.right(), middle.right()) - Math.max(line.left(), middle.left());
            if (own >= 0.5 * Math.min(line.width, middle.width)) return true;
        }
        return false;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
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
