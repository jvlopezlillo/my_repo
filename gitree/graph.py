"""Cálculo del grafo de commits (carriles), independiente de Qt."""
from __future__ import annotations

from dataclasses import dataclass, field

from .git import Commit


@dataclass
class GraphRow:
    col: int
    color: int
    # (x0, y0, x1, y1, color): x en carriles, y en fracción de la altura de la fila
    segments: list[tuple[float, float, float, float, int]] = field(default_factory=list)


def build_graph(commits: list[Commit]) -> tuple[list[GraphRow], int]:
    lanes: list[str | None] = []
    colors: list[int] = []
    next_color = 0
    rows: list[GraphRow] = []
    max_lanes = 1

    def alloc(value: str, color: int) -> int:
        for i, h in enumerate(lanes):
            if h is None:
                lanes[i], colors[i] = value, color
                return i
        lanes.append(value)
        colors.append(color)
        return len(lanes) - 1

    for c in commits:
        incoming = [i for i, h in enumerate(lanes) if h == c.hash]
        if incoming:
            col = incoming[0]
            color = colors[col]
        else:  # punta de rama: carril nuevo
            color = next_color
            next_color += 1
            col = alloc(c.hash, color)
        segs: list[tuple[float, float, float, float, int]] = []
        for i, h in enumerate(lanes):
            if h is not None and h != c.hash:
                segs.append((i, 0, i, 1, colors[i]))
        for i in incoming:
            segs.append((i, 0, col, 0.5, colors[i]))
        for i in incoming + [col]:
            lanes[i] = None
        for n, p in enumerate(c.parents):
            if p in lanes:
                j = lanes.index(p)
            elif n == 0:
                lanes[col], colors[col] = p, color
                j = col
            else:
                j = alloc(p, next_color)
                next_color += 1
            segs.append((col, 0.5, j, 1, colors[j]))
        while lanes and lanes[-1] is None:
            lanes.pop()
            colors.pop()
        rows.append(GraphRow(col, color, segs))
        max_lanes = max([max_lanes, col + 1] + [1 + int(max(s[0], s[2])) for s in segs])
    return rows, max_lanes
