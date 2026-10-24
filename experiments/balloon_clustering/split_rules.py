"""Groups recognized comic lines into speech balloons.

Two sets of rules, for the two kinds of text:

Horizontal text (centred or left-aligned lines)
  Lines of one balloon are set with each other (centres or left edges within
  a share of the line height) and no line of another balloon runs across the
  column between a pair of them. A gap wider than the usual line pitch ends a
  balloon (the fill of a balloon or box widens the gap at its edges).

Vertical text (top-aligned columns, right to left)
  A line joins another when the gap in both directions is small: on one row
  (tops within ROW) and at most SIDE apart, with no other column between them;
  or right below in the same column (right edges within COLUMN, at most
  BELOW apart).

Input: a text file of "x y width height text" per line.
Output: the balloons in reading order and their lines.
"""
import io
import re
import sys

TALL = 1.5          # a line this much taller than wide is vertical text
ROW = 8             # vertical: tops within this many pixels are one row
SIDE = 70           # vertical: columns of one balloon are at most this far apart
COLUMN = 8          # vertical: one column's right edges are this close
BELOW = 40          # vertical: stacked lines of one balloon are this far apart
EDGE = 0.3          # horizontal: centres or left edges within this share of the height
GAP_HEIGHTS = 1.6   # horizontal: lines further apart than this many heights are apart
PITCH = 1.7         # horizontal: ... nor than this many usual pitches


def load(path):
    lines = []
    pattern = re.compile(r'^\s*(-?[\d.]+),\s*(-?[\d.]+)\s+([\d.]+)x\s*([\d.]+)\s?(.*)$')
    with io.open(path, encoding='utf-8') as f:
        for row in f:
            m = pattern.match(row)
            if not m:
                continue
            lines.append({'x': float(m.group(1)), 'y': float(m.group(2)),
                          'w': float(m.group(3)), 'h': float(m.group(4)),
                          'text': m.group(5).strip()})
    return lines


def right(line):
    return line['x'] + line['w']


def vertical(lines):
    tall = sum(1 for line in lines if line['h'] > line['w'] * TALL)
    return tall * 2 > len(lines)


def groups_of(lines, links):
    parent = list(range(len(lines)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i, j in links:
        a, b = find(i), find(j)
        if a != b:
            parent[a] = b
    groups = {}
    for i in range(len(lines)):
        groups.setdefault(find(i), []).append(lines[i])
    return list(groups.values())


# --- vertical text -----------------------------------------------------------

def vertical_links(lines):
    links = []
    for i in range(len(lines)):
        for j in range(i + 1, len(lines)):
            a, b = lines[i], lines[j]
            side = max(a['x'], b['x']) - min(right(a), right(b))
            below = max(a['y'], b['y']) - min(a['y'] + a['h'], b['y'] + b['h'])
            if abs(a['y'] - b['y']) <= ROW and side <= SIDE:
                left, rgt = (a, b) if a['x'] <= b['x'] else (b, a)
                if not column_between(lines, left, rgt):
                    links.append((i, j))
                    continue
            if abs(right(a) - right(b)) <= COLUMN and below <= BELOW:
                links.append((i, j))
    return links


def column_between(lines, left, rgt):
    """Another line on their row stands between the two."""
    for other in lines:
        if other is left or other is rgt:
            continue
        if abs(other['y'] - left['y']) > ROW:
            continue
        if other['x'] > left['x'] and right(other) < rgt['x']:
            return True
    return False


# --- horizontal text ---------------------------------------------------------

def alignment(a, b):
    """How two lines are set, or None: 'left' or 'centred'. A pair that is
    both counts as 'left' and 'centred' at once (a left-aligned paragraph's
    lines may also happen to be centred)."""
    edge = EDGE * max(a['h'], b['h'])
    ways = []
    if abs(a['x'] - b['x']) <= edge:
        ways.append('left')
    if abs((a['x'] + a['w'] / 2) - (b['x'] + b['w'] / 2)) <= edge:
        ways.append('centred')
    return ways


def pitch_of(lines):
    tops = sorted(line['y'] for line in lines)
    diffs = [round((b - a) / 2) * 2 for a, b in zip(tops, tops[1:]) if b - a > 0]
    return max(set(diffs), key=diffs.count) if diffs else 20


def between_horizontal(lines, a, b, middle, pitch):
    """A line of another balloon lies between a and b.

    It starts below a and above b, and it runs across their column: most of
    their shared width, or right beside them with much of its own width.
    """
    top, low = (a, b) if a['y'] <= b['y'] else (b, a)
    if not top['y'] + 0.5 * top['h'] < middle['y'] < low['y'] - 0.5 * low['h']:
        return False
    left = max(a['x'], b['x'])
    rgt = min(a['x'] + a['w'], b['x'] + b['w'])
    shared = rgt - left
    overlap = min(rgt, middle['x'] + middle['w']) - max(left, middle['x'])
    if shared > 0 and overlap >= 0.5 * shared:
        return middle['y'] - top['y'] > PITCH * pitch
    for line in (a, b):
        overlap = min(line['x'] + line['w'], middle['x'] + middle['w']) \
            - max(line['x'], middle['x'])
        if overlap >= 0.5 * min(line['w'], middle['w']):
            return True
    return False


def horizontal_links(lines, way, pitch):
    links = []
    for i in range(len(lines)):
        for j in range(i + 1, len(lines)):
            a, b = lines[i], lines[j]
            h = max(a['h'], b['h'])
            if abs(a['y'] - b['y']) > GAP_HEIGHTS * pitch or b['y'] - a['y'] > PITCH * pitch:
                continue
            if way not in alignment(a, b):
                continue
            if any(between_horizontal(lines, a, b, lines[k], pitch)
                   for k in range(len(lines)) if k != i and k != j):
                continue
            links.append((i, j))
    return links


def bounds(group):
    """A balloon's box: left, top, right, bottom."""
    left = min(l['x'] for l in group)
    top = min(l['y'] for l in group)
    return left, top, max(right(l) for l in group), max(l['y'] + l['h'] for l in group)


def same_row(a, b):
    """Two balloons on one row: their heights overlap."""
    top = max(a[1], b[1])
    bottom = min(a[3], b[3])
    return bottom - top >= 0.5 * min(a[3] - a[1], b[3] - b[1])


def order_balloons(groups):
    """The balloons in reading order: top to bottom, and the balloons on one
    row (their heights overlap) left to right first."""
    rows = []
    for group in sorted(groups, key=lambda g: bounds(g)[1]):
        box = bounds(group)
        for row in rows:
            if same_row(box, row[0][0]):
                row.append((box, group))
                break
        else:
            rows.append([(box, group)])
    ordered = []
    for row in rows:
        row.sort(key=lambda entry: entry[0][0])
        ordered.extend(group for _, group in row)
    return ordered


def main():
    lines = load(sys.argv[1])
    if vertical(lines):
        groups = groups_of(lines, vertical_links(lines))
        bubble = 'vertical'
        groups = order_balloons(groups)
        order = lambda g: sorted(g, key=lambda l: (-right(l), l['y']))
    else:
        pitch = pitch_of(lines)
        by_way = {'centred': groups_of(lines, horizontal_links(lines, 'centred', pitch)),
                  'left': groups_of(lines, horizontal_links(lines, 'left', pitch))}
        biggest = {w: max(len(g) for g in gs) for w, gs in by_way.items()}
        bubble = 'centred' if biggest['centred'] >= biggest['left'] else 'left'
        groups = by_way[bubble]
        groups = order_balloons(groups)
        order = lambda g: sorted(g, key=lambda l: (l['y'], l['x']))
    out = ['%d lines -> %d balloons (%s)' % (len(lines), len(groups), bubble)]
    for i, g in enumerate(groups):
        out.append('%2d. %s' % (i + 1, ' '.join(l['text'] for l in order(g))))
    io.open(sys.argv[1] + '.result.txt', 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print('written', sys.argv[1] + '.result.txt')


if __name__ == '__main__':
    main()
