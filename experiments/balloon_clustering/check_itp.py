"""Checks the app's balloon grouping (as ported to PanelMerger) on an .itp
project: every image's lines are grouped into balloons and the result is
printed balloon by balloon in reading order.

    python check_itp.py [--estimate] <project folder or .itp file> [...]

Without --estimate a line belongs to the smallest panel that holds it (the
.itp's panels, with 18 px of slack, as in PanelMerger); with --estimate the
panels are ignored and the balloons are worked out from the lines alone, as
the app does when it has no panels from the detector.

As in the app, the lines that fall in no panel are not joined to a panel:
they are grouped among themselves by their distances. A balloon also does
not swallow a line that lies far from it: lines taken in by a panel's box
but not close to the panel's other lines are worked out on their own.
"""
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import split_rules as rules  # noqa: E402

SLACK = 18          # PanelMerger's AROUND_CENTER
NEAR = 1.7          # a line further than this many pitches from the nearest
                    # line of its balloon is in no balloon


def itp_files(argument):
    if os.path.isdir(argument):
        return sorted(os.path.join(argument, f) for f in os.listdir(argument)
                      if f.endswith('.itp'))
    return [argument]


def lines_of(image):
    lines = []
    for box in image.get('boxes') or []:
        geo = box.get('geometry') or {}
        x = geo.get('X', geo.get('x', 0))
        y = geo.get('Y', geo.get('y', 0))
        w = geo.get('width', 0)
        h = geo.get('height', 0)
        text = box.get('text', box.get('source', ''))
        if w <= 0 or h <= 0 or not text:
            continue
        lines.append({'x': x, 'y': y, 'w': w, 'h': h, 'text': text})
    return lines


def panels_of(image):
    panels = []
    for p in image.get('panels') or []:
        panels.append({'x': p['X'], 'y': p['Y'], 'w': p['width'], 'h': p['height']})
    return panels


def inside(panel, line, slack):
    return (line['x'] >= panel['x'] - slack and line['y'] >= panel['y'] - slack
            and line['x'] + line['w'] <= panel['x'] + panel['w'] + slack
            and line['y'] + line['h'] <= panel['y'] + panel['h'] + slack)


def panel_of(lines, panels):
    """The index of the smallest panel holding each line, or None."""
    ordered = sorted(panels, key=lambda p: p['w'] * p['h'])
    owners = []
    for line in lines:
        owner = None
        for panel in ordered:
            if inside(panel, line, SLACK):
                owner = panels.index(panel)
                break
        owners.append(owner)
    return owners


def estimated(lines):
    """The balloons the app works out from the lines alone."""
    if not lines:
        return []
    if rules.vertical(lines):
        return rules.groups_of(lines, rules.vertical_links(lines))
    pitch = rules.pitch_of(lines)
    by_way = {'centred': rules.groups_of(lines, rules.horizontal_links(lines, 'centred', pitch)),
              'left': rules.groups_of(lines, rules.horizontal_links(lines, 'left', pitch))}
    biggest = {w: max(len(g) for g in gs) for w, gs in by_way.items()}
    return by_way['centred'] if biggest['centred'] >= biggest['left'] else by_way['left']


def near(a, b, pitch):
    """Two lines close enough to be in one balloon."""
    if rules.vertical([a, b]):
        # Verticals: on one row and a column apart, or stacked in a column.
        side = max(a['x'], b['x']) - min(rules.right(a), rules.right(b))
        below = max(a['y'], b['y']) - min(a['y'] + a['h'], b['y'] + b['h'])
        return (abs(a['y'] - b['y']) <= rules.ROW and side <= rules.SIDE) or \
            (abs(rules.right(a) - rules.right(b)) <= rules.COLUMN and below <= rules.BELOW)
    return abs(a['y'] - b['y']) <= NEAR * pitch and \
        abs(a['y'] + a['h'] - b['y'] - b['h']) <= NEAR * pitch


def far_apart(group, pitch):
    """The lines of a group that are far from every other line of it."""
    far = []
    for line in group:
        if all(near(line, other, pitch) for other in group if other is not line):
            continue
        if any(near(line, other, pitch) for other in group if other is not line):
            continue
        far.append(line)
    return far


def group_lines(lines, panels):
    """The balloons: the panels' own lines, and the others by distance."""
    owners = panel_of(lines, panels) if panels else [None] * len(lines)
    pitch = rules.pitch_of(lines) if lines and not rules.vertical(lines) else 0
    within = {}
    outside = []
    for line, owner in zip(lines, owners):
        if owner is None:
            outside.append(line)
        else:
            within.setdefault(owner, []).append(line)
    groups = []
    for owner in sorted(within):
        group = within[owner]
        # Lines the panel's box took in without being in its balloon.
        far = far_apart(group, pitch) if len(group) > 1 else []
        keep = [l for l in group if l not in far]
        if keep:
            groups.append(keep)
        outside.extend(far)
    if outside:
        groups.extend(estimated(outside))
    return groups


def main():
    arguments = [a for a in sys.argv[1:] if not a.startswith('--')]
    estimate = '--estimate' in sys.argv[1:]
    out = []
    for argument in arguments:
        for path in itp_files(argument):
            project = json.load(io.open(path, encoding='utf-8'))
            known_vertical = (project.get('settings') or {}).get('sourceLang') == 'ja'
            for name, image in (project.get('images') or {}).items():
                lines = lines_of(image)
                if not lines:
                    continue
                panels = [] if estimate else panels_of(image)
                vertical = known_vertical or rules.vertical(lines)
                grouped = group_lines(lines, panels)
                ordered = rules.order_balloons(grouped, vertical)
                out.append('%s / %s: %d lines, %d panels (%s) -> %d balloons'
                           % (os.path.basename(os.path.dirname(path)), name,
                              len(lines), len(panels),
                              'estimated' if estimate else 'from the .itp',
                              len(ordered)))
                for i, group in enumerate(ordered):
                    out.append('%3d. %s' % (i + 1, ' '.join(l['text'] for l in group)))
    text = '\n'.join(out) + '\n'
    target = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'check_itp.result.txt')
    io.open(target, 'w', encoding='utf-8').write(text)
    print('written', target)


if __name__ == '__main__':
    main()
