# Balloon clustering experiment

These files were used to work out how the recognized lines of a comic page
are grouped into balloons (one per speech balloon) before `PanelMerger`
merges them into blocks and the text is translated.

`split_rules.py` reads lines as `x, y widthxheight text` (the boxes of a
`.itp` file or the `PanelMerger` log) and prints the balloons. It is plain
Python (no third-party packages):

    python split_rules.py avengers.txt     # writes avengers.txt.result.txt

## The rules it settled on

Two sets, chosen by the page (vertical text: most lines much taller than
wide):

- **Horizontal text** — lines of one balloon are set with each other (left
  edges or centres within 0.3 of the line height; a pair that fits both
  counts for both) and no line of another balloon runs across the column
  between them. A gap wider than 1.7 of the usual line pitch ends a
  balloon.
- **Vertical text** — the gaps in both directions must be small: lines on
  one row (tops within 8 px) with at most a 70 px gap side by side and no
  column between them; or lines in one column (right edges within 8 px) at
  most 40 px apart vertically.

## Order of the balloons

The balloons are laid out in reading order after they are found: rows from
top to bottom, where two balloons are on one row when their heights overlap
(by half the shorter one), and the balloons of a row from left to right.

## Results on the sample pages

| page | lines | balloons in ImageTrans | found |
|---|---|---|---|
| `avengers.txt` | 40 | 15 | 15 (all correct) |
| `prose.txt` | 8 | 3 | 3 (all correct) |
| `conan.txt` (vertical) | 17 | 7 | 6 (two neighbouring bubbles on one row, 25 px apart, are joined) |
