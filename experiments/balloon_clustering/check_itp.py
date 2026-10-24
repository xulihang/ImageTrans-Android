"""Checks the app's balloon grouping (as ported to PanelMerger) on an .itp
project: every image's lines are grouped into balloons, a panel being the
box ImageTrans itself gave each line (the .itp's panels), and the result is
printed balloon by balloon in reading order.

    python check_itp.py <project folder or .itp file> [...]

The lines are the .itp boxes (x, y, width, height, text); a line belongs to
the smallest panel that holds it, with 18 px of slack, as in PanelMerger.
"""
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from split_rules import order_balloons  # noqa: E402

SLACK = 18


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


def group_lines(lines, panels):
    """The lines grouped by panel: the smallest panel holding each line."""
    if not panels:
        return [[line] for line in lines]
    ordered = sorted(panels, key=lambda p: p['w'] * p['h'])
    groups = {}
    loose = []
    for line in lines:
        for index, panel in enumerate(ordered):
            if inside(panel, line, SLACK):
                groups.setdefault(panels.index(panel), []).append(line)
                break
        else:
            loose.append(line)
    if loose:
        groups['no panel'] = loose
    return [groups[key] for key in sorted(groups, key=lambda k: (isinstance(k, str), k))]


def bound(group):
    left = min(l['x'] for l in group)
    top = min(l['y'] for l in group)
    right = max(l['x'] + l['w'] for l in group)
    bottom = max(l['y'] + l['h'] for l in group)
    return left, top, right, bottom


def main():
    out = []
    for argument in sys.argv[1:]:
        for path in itp_files(argument):
            project = json.load(io.open(path, encoding='utf-8'))
            vertical = (project.get('settings') or {}).get('sourceLang') == 'ja'
            for name, image in (project.get('images') or {}).items():
                lines = lines_of(image)
                panels = panels_of(image)
                if not lines:
                    continue
                grouped = group_lines(lines, panels)
                ordered = order_balloons(grouped, vertical) if panels else grouped
                out.append('%s / %s: %d lines, %d panels -> %d balloons'
                           % (os.path.basename(os.path.dirname(path)), name,
                              len(lines), len(panels), len(ordered)))
                for i, group in enumerate(ordered):
                    out.append('%3d. %s' % (i + 1, ' '.join(l['text'] for l in group)))
    text = '\n'.join(out) + '\n'
    target = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'check_itp.result.txt')
    io.open(target, 'w', encoding='utf-8').write(text)
    print('written', target)


if __name__ == '__main__':
    main()
