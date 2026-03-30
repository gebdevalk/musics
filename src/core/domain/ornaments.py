# ornaments.py

from __future__ import annotations

from typing import List, Callable, Dict

from core.domain.leafs import Leaf
from tools.ratio import Ratio

# -----------------------------
# Ornament functions
# -----------------------------

def ornamented(leaf: Leaf, ornament: str) -> List[Leaf]:
    ornament_type = ornament.replace("\\", "")
    func = ornament_function_map.get(ornament_type, plain)
    return func(leaf)


def plain(leaf: Leaf) -> List[Leaf]:
    return [leaf]


def _base_kwargs(leaf: Leaf) -> dict:
    return dict(key=leaf.key, volume=leaf.volume,
                articulation=leaf.articulation, tied=leaf.tied)


def prall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    return [
        Leaf(duration=value8,                     pitches=leaf.pitches,                                   **kw),
        Leaf(duration=value8,                     pitches=[local_scale.upper(p) for p in leaf.pitches],   **kw),
        Leaf(duration=leaf.duration * Ratio(3, 4), pitches=leaf.pitches,                      **kw),
    ]


def prallup(leaf: Leaf) -> List[Leaf]:
    return [leaf]


def pralldown(leaf: Leaf) -> List[Leaf]:
    return [leaf]


def upprall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[local_scale.lower(p) for p in leaf.pitches], **kw)
    return [low, base, high, base, high, base, high, base]


def downprall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[local_scale.lower(p) for p in leaf.pitches], **kw)
    return [high, base, low, base, high, base, high, base]


def prallprall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    return [
        base, high, base, high,
        Leaf(duration=leaf.duration * Ratio(1, 2), pitches=leaf.pitches, **kw),
    ]


def lineprall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value16 = leaf.duration / 16
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value16, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value16, pitches=leaf.pitches, **kw)
    return [
        Leaf(duration=leaf.duration / 2,  pitches=[local_scale.upper(p) for p in leaf.pitches], **kw),
        base, high, base, high,
        Leaf(duration=leaf.duration / 4,  pitches=leaf.pitches, **kw),
    ]


def prallmordent(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[local_scale.lower(p) for p in leaf.pitches], **kw)
    return [high, base, high, base, high, base, low, base]


def mordent(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    return [
        Leaf(duration=value8,                     pitches=leaf.pitches,                                 **kw),
        Leaf(duration=value8,                     pitches=[local_scale.lower(p) for p in leaf.pitches], **kw),
        Leaf(duration=leaf.duration * Ratio(3, 4), pitches=leaf.pitches,                    **kw),
    ]


def upmordent(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[local_scale.lower(p) for p in leaf.pitches], **kw)
    return [low, base, high, base, high, base, low, base]


def downmordent(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 12
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[local_scale.lower(p) for p in leaf.pitches], **kw)
    return [
        high, base, low, base,
        high, base, high, base,
        high, base, low, base,
    ]


def trill(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    return [
        high, base, high, base, high,
        Leaf(duration=leaf.duration * Ratio(3, 8), pitches=leaf.pitches, **kw),
    ]


def turn(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value4 = leaf.duration / 4
    kw = _base_kwargs(leaf)
    base = Leaf(duration=value4, pitches=leaf.pitches, **kw)
    return [
        Leaf(duration=value4, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw),
        base,
        Leaf(duration=value4, pitches=[local_scale.lower(p) for p in leaf.pitches], **kw),
        base,
    ]


def reverseturn(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value4 = leaf.duration / 4
    kw = _base_kwargs(leaf)
    base = Leaf(duration=value4, pitches=leaf.pitches, **kw)
    return [
        Leaf(duration=value4, pitches=[local_scale.lower(p) for p in leaf.pitches], **kw),
        base,
        Leaf(duration=value4, pitches=[local_scale.upper(p) for p in leaf.pitches], **kw),
        base,
    ]


def shortfermata(leaf: Leaf) -> List[Leaf]:
    kw = _base_kwargs(leaf)
    return [Leaf(duration=leaf.duration * Ratio(3, 2), pitches=leaf.pitches, **kw)]


def fermata(leaf: Leaf) -> List[Leaf]:
    kw = _base_kwargs(leaf)
    return [Leaf(duration=leaf.duration * Ratio(4, 2), pitches=leaf.pitches, **kw)]


def longfermata(leaf: Leaf) -> List[Leaf]:
    kw = _base_kwargs(leaf)
    return [Leaf(duration=leaf.duration * Ratio(6, 2), pitches=leaf.pitches, **kw)]


def verylongfermata(leaf: Leaf) -> List[Leaf]:
    kw = _base_kwargs(leaf)
    return [Leaf(duration=leaf.duration * Ratio(8, 2), pitches=leaf.pitches, **kw)]


# -----------------------------
# Ornament dispatch table
# -----------------------------

ornament_function_map: Dict[str, Callable[[Leaf], List[Leaf]]] = {
    "prall":          prall,
    "prallup":        prallup,
    "pralldown":      pralldown,
    "upprall":        upprall,
    "downprall":      downprall,
    "prallprall":     prallprall,
    "lineprall":      lineprall,
    "prallmordent":   prallmordent,
    "mordent":        mordent,
    "upmordent":      upmordent,
    "downmordent":    downmordent,
    "trill":          trill,
    "turn":           turn,
    "reverseturn":    reverseturn,
    "shortfermata":   shortfermata,
    "fermata":        fermata,
    "longfermata":    longfermata,
    "verylongfermata": verylongfermata,
}
