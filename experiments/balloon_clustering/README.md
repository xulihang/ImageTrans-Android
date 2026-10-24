# Balloon clustering experiment

These files were used to work out how the recognized lines of a comic page
are grouped into speech balloons before `PanelMerger` merges them into
blocks and the text is translated, and how the balloons are then put in
reading order.

`split_rules.py` reads lines as `x, y widthxheight text` (the boxes of a
`.itp` file or the `PanelMerger` log) and prints the balloons. It is plain
Python (no third-party packages):

    python split_rules.py avengers.txt     # writes avengers.txt.result.txt

## The rules

Four steps, the same order the app takes them in.

### 1. Which way the text runs

A page is vertical when more than half its lines are much taller than wide
(height > 1.5 × width); otherwise horizontal.

### 2. Grouping the lines into balloons

**Horizontal text** (English, Chinese paragraphs)

- Two lines are set alike when their left edges, or their centres, are within
  0.3 of the line height. A pair that fits both counts for both (a
  left-aligned paragraph's lines may also happen to be centred).
- Their tops are at most 1.7 of the page's usual line pitch apart. The pitch
  is the most common distance between neighboring tops (the smaller one when
  two are equally common).
- Nothing of another balloon lies between them: a line that covers most of
  their shared width, further below the upper one than 1.7 pitches, or that
  runs beside them with most of its own width, separates them.

**Vertical text** (manga)

- On one row: tops within 8 px, at most 70 px apart horizontally, and no
  other column between them.
- In one column: right edges within 8 px, at most 40 px apart vertically.

Groups are transitive: a line joins by any of these links.

### 3. Merging a balloon's lines into blocks

**Horizontal**: the fragments on one line join left to right (rows overlapping
by 60% of the shorter one and touching sideways); lines join into blocks top
to bottom when they overlap sideways by at least 50% of the narrower one.

**Vertical**: the fragments of a column join top to bottom; the columns of a
block follow in the order of its lines.

### 4. Order of the balloons

Two balloons are on one row when their heights overlap by at least half the
shorter one; a row comes top to bottom; the balloons of a row go **right to
left** for vertical text (a manga reads from the right) and **left to
right** for horizontal text. Lines inside a balloon keep their own order.

## Results on the sample pages

| page | lines | balloons in ImageTrans | found |
|---|---|---|---|
| `avengers.txt` | 40 | 15 | 15, all correct |
| `prose.txt` | 8 | 3 | 3, all correct |
| `conan.txt` (vertical) | 17 | 7 | 6: two neighbouring bubbles on one row, 25 px apart, are joined |
