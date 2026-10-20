package com.basiccat.imagetrans;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Port of the extension's mergeTextBoxes(): merge line fragments into
 * paragraphs before translation. This keeps the app's boxes close to what the
 * browser extension produces.
 */
public final class TextBoxMerger {
    private TextBoxMerger() {
    }

    public static List<Box> merge(List<Box> items, String sourceLang,
                                  double xSpacing, double ySpacing) {
        if (items == null || items.isEmpty()) return new ArrayList<>();
        final double xGap = xSpacing > 0 ? xSpacing : 15;
        final double yGap = ySpacing > 0 ? ySpacing : 15;

        List<Double> heights = new ArrayList<>();
        for (Box box : items) {
            heights.add(Math.abs(box.height));
        }
        Collections.sort(heights);
        double medianHeight = heights.get(heights.size() / 2);
        final double lineThreshold = Math.max(1, medianHeight * 0.5);

        List<Box> sorted = new ArrayList<>(items);
        Collections.sort(sorted, new Comparator<Box>() {
            @Override
            public int compare(Box a, Box b) {
                if (Math.abs(a.top() - b.top()) < lineThreshold) {
                    return Double.compare(a.left(), b.left());
                }
                return Double.compare(a.top(), b.top());
            }
        });

        int tallCount = 0;
        for (Box item : items) {
            if (item.height > item.width) tallCount++;
        }
        final boolean isVertical = tallCount > items.size() / 2;
        final boolean isRTL = "ar".equals(sourceLang) || "fa".equals(sourceLang)
                || "he".equals(sourceLang) || "ur".equals(sourceLang);

        // Phase 1: merge fragments on the same line.
        List<List<Box>> lines = new ArrayList<>();
        boolean[] used1 = new boolean[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            if (used1[i]) continue;
            List<Box> line = new ArrayList<>();
            line.add(sorted.get(i));
            used1[i] = true;

            boolean changed = true;
            while (changed) {
                changed = false;
                for (int j = 0; j < sorted.size(); j++) {
                    if (used1[j]) continue;
                    for (int k = 0; k < line.size(); k++) {
                        Box candidate = sorted.get(j);
                        if (yOverlapRatio(line.get(k), candidate) >= 0.15
                                && xCloseEnough(line.get(k), candidate, xGap)) {
                            line.add(candidate);
                            used1[j] = true;
                            changed = true;
                            break;
                        }
                    }
                }
            }

            Collections.sort(line, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    if (isRTL) return Double.compare(b.left(), a.left());
                    return Double.compare(a.left(), b.left());
                }
            });
            lines.add(line);
        }

        // Phase 2: merge lines into blocks.
        List<List<Box>> groups = new ArrayList<>();
        boolean[] used2 = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (used2[i]) continue;
            List<Box> group = new ArrayList<>(lines.get(i));
            used2[i] = true;

            boolean changed = true;
            while (changed) {
                changed = false;
                for (int j = 0; j < lines.size(); j++) {
                    if (used2[j]) continue;
                    List<Box> other = lines.get(j);
                    boolean matched = false;
                    for (Box a : group) {
                        for (Box b : other) {
                            if (xOverlapRatio(a, b) >= 0.15 && yCloseEnough(a, b, yGap)) {
                                matched = true;
                                break;
                            }
                        }
                        if (matched) break;
                    }
                    if (matched) {
                        group.addAll(other);
                        used2[j] = true;
                        changed = true;
                    }
                }
            }

            Collections.sort(group, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    if (isVertical) {
                        return Double.compare(b.left(), a.left());
                    }
                    double minHeight = Math.min(Math.abs(a.height), Math.abs(b.height));
                    if (Math.abs(a.top() - b.top()) < minHeight * 0.5) {
                        if (isRTL) return Double.compare(b.left(), a.left());
                        return Double.compare(a.left(), b.left());
                    }
                    return Double.compare(a.top(), b.top());
                }
            });
            groups.add(group);
        }

        List<Box> merged = new ArrayList<>();
        boolean cjkText = false;
        for (Box item : items) {
            if (containsCjk(item.source)) {
                cjkText = true;
                break;
            }
        }
        for (List<Box> group : groups) {
            StringBuilder text = new StringBuilder();
            boolean noSpace = "zh".equals(sourceLang) || "ja".equals(sourceLang)
                    || "th".equals(sourceLang) || cjkText;
            for (int i = 0; i < group.size(); i++) {
                Box box = group.get(i);
                if (i > 0 && !noSpace) text.append(' ');
                text.append(box.source == null ? "" : box.source.trim());
            }

            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (Box box : group) {
                minX = Math.min(minX, box.left());
                minY = Math.min(minY, box.top());
                maxX = Math.max(maxX, box.right());
                maxY = Math.max(maxY, box.bottom());
            }
            Box union = new Box(minX, minY, maxX - minX, maxY - minY, text.toString());
            merged.add(union);
        }

        if (isVertical) {
            Collections.sort(merged, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    return Double.compare(b.left(), a.left());
                }
            });
        } else if (isRTL) {
            Collections.sort(merged, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    return Double.compare(b.left(), a.left());
                }
            });
        } else {
            Collections.sort(merged, new Comparator<Box>() {
                @Override
                public int compare(Box a, Box b) {
                    if (Math.abs(a.top() - b.top()) < lineThreshold) {
                        return Double.compare(a.left(), b.left());
                    }
                    return Double.compare(a.top(), b.top());
                }
            });
        }

        return merged;
    }

    private static boolean containsCjk(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ((ch >= 0x4E00 && ch <= 0x9FFF)
                    || (ch >= 0x3400 && ch <= 0x4DBF)
                    || (ch >= 0x3040 && ch <= 0x30FF)
                    || (ch >= 0xAC00 && ch <= 0xD7AF)
                    || (ch >= 0x3000 && ch <= 0x303F)
                    || (ch >= 0xFF00 && ch <= 0xFFEF)) {
                return true;
            }
        }
        return false;
    }

    private static double xOverlapRatio(Box a, Box b) {
        double aW = Math.abs(a.width);
        double bW = Math.abs(b.width);
        double marginA = Math.min(aW * 0.25, 20);
        double marginB = Math.min(bW * 0.25, 20);
        double overlap = Math.min(a.right() + marginA, b.right() + marginB)
                - Math.max(a.left() - marginA, b.left() - marginB);
        if (overlap <= 0) return 0;
        double minW = Math.min(aW, bW);
        if (minW <= 0) return 0;
        return overlap / minW;
    }

    private static boolean xCloseEnough(Box a, Box b, double xGap) {
        double gap;
        if (a.right() < b.left()) gap = b.left() - a.right();
        else if (b.right() < a.left()) gap = a.left() - b.right();
        else gap = 0;
        return gap <= xGap;
    }

    private static double yOverlapRatio(Box a, Box b) {
        double aH = Math.abs(a.height);
        double bH = Math.abs(b.height);
        double marginA = Math.min(aH * 0.25, 30);
        double marginB = Math.min(bH * 0.25, 30);
        double overlap = Math.min(a.bottom() + marginA, b.bottom() + marginB)
                - Math.max(a.top() - marginA, b.top() - marginB);
        if (overlap <= 0) return 0;
        double minH = Math.min(aH, bH);
        if (minH <= 0) return 0;
        return overlap / minH;
    }

    private static boolean yCloseEnough(Box a, Box b, double yGap) {
        double gap;
        if (a.bottom() < b.top()) gap = b.top() - a.bottom();
        else if (b.bottom() < a.top()) gap = a.top() - b.bottom();
        else gap = 0;
        return gap <= yGap;
    }
}
