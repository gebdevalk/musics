from __future__ import annotations

from typing import List, Callable, Dict

from core.domain.leafs import Leaf
from tools.ratio import Ratio


# -----------------------------
# Ornament functions
# -----------------------------

def ornamented(leaf: Leaf) -> List[Leaf]:
    ornament_type = leaf.text.replace("\\", "")
    func = ornament_function_map.get(ornament_type, plain)
    return func(leaf)


def plain(leaf: Leaf) -> List[Leaf]:
    return [leaf]


def prall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    return [
        Leaf(value8, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties),
        Leaf(value8, [local_scale.upper(p) for p in leaf.pitches],
             leaf.key, leaf.volume, leaf.articulation, leaf.ties),
        Leaf(leaf.duration * Ratio(3, 4),
             leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    ]


def prallup(leaf: Leaf) -> List[Leaf]:
    return [leaf]


def pralldown(leaf: Leaf) -> List[Leaf]:
    return [leaf]


def upprall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    high = Leaf(value8, [local_scale.upper(p) for p in leaf.pitches],
                leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    base = Leaf(value8, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    low = Leaf(value8, [local_scale.lower(p) for p in leaf.pitches],
               leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [low, base, high, base, high, base, high, base]


def downprall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    high = Leaf(value8, [local_scale.upper(p) for p in leaf.pitches],
                leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    base = Leaf(value8, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    low = Leaf(value8, [local_scale.lower(p) for p in leaf.pitches],
               leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [high, base, low, base, high, base, high, base]


def prallprall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    high = Leaf(value8, [local_scale.upper(p) for p in leaf.pitches],
                leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    base = Leaf(value8, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [
        base, high, base, high,
        Leaf(leaf.duration * Ratio(1, 2),
             leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    ]


def lineprall(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value16 = leaf.duration / 16
    high = Leaf(value16, [local_scale.upper(p) for p in leaf.pitches],
                leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    base = Leaf(value16, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [
        Leaf(leaf.duration / 2,
             [local_scale.upper(p) for p in leaf.pitches],
             leaf.key, leaf.volume, leaf.articulation, leaf.ties),
        base, high, base, high,
        Leaf(leaf.duration / 4,
             leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    ]


def prallmordent(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    high = Leaf(value8, [local_scale.upper(p) for p in leaf.pitches],
                leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    base = Leaf(value8, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    low = Leaf(value8, [local_scale.lower(p) for p in leaf.pitches],
               leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [high, base, high, base, high, base, low, base]


def mordent(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    return [
        Leaf(value8, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties),
        Leaf(value8, [local_scale.lower(p) for p in leaf.pitches],
             leaf.key, leaf.volume, leaf.articulation, leaf.ties),
        Leaf(leaf.duration * Ratio(3, 4),
             leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    ]


def upmordent(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    high = Leaf(value8, [local_scale.upper(p) for p in leaf.pitches],
                leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    base = Leaf(value8, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    low = Leaf(value8, [local_scale.lower(p) for p in leaf.pitches],
               leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [low, base, high, base, high, base, low, base]


def downmordent(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 12
    high = Leaf(value8, [local_scale.upper(p) for p in leaf.pitches],
                leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    base = Leaf(value8, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    low = Leaf(value8, [local_scale.lower(p) for p in leaf.pitches],
               leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [
        high, base, low, base,
        high, base, high, base,
        high, base, low, base
    ]


def trill(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value8 = leaf.duration / 8
    high = Leaf(value8, [local_scale.upper(p) for p in leaf.pitches],
                leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    base = Leaf(value8, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [
        high, base, high, base, high,
        Leaf(leaf.duration * Ratio(3, 8),
             leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    ]


def turn(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value4 = leaf.duration / 4
    base = Leaf(value4, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [
        Leaf(value4, [local_scale.upper(p) for p in leaf.pitches],
             leaf.key, leaf.volume, leaf.articulation, leaf.ties),
        base,
        Leaf(value4, [local_scale.lower(p) for p in leaf.pitches],
             leaf.key, leaf.volume, leaf.articulation, leaf.ties),
        base
    ]


def reverseturn(leaf: Leaf) -> List[Leaf]:
    local_scale = leaf.key.scale
    value4 = leaf.duration / 4
    base = Leaf(value4, leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)
    return [
        Leaf(value4, [local_scale.lower(p) for p in leaf.pitches],
             leaf.key, leaf.volume, leaf.articulation, leaf.ties),
        base,
        Leaf(value4, [local_scale.upper(p) for p in leaf.pitches],
             leaf.key, leaf.volume, leaf.articulation, leaf.ties),
        base
    ]


def shortfermata(leaf: Leaf) -> List[Leaf]:
    return [Leaf(leaf.duration * Ratio(3, 2),
                 leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)]


def fermata(leaf: Leaf) -> List[Leaf]:
    return [Leaf(leaf.duration * Ratio(4, 2),
                 leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)]


def longfermata(leaf: Leaf) -> List[Leaf]:
    return [Leaf(leaf.duration * Ratio(6, 2),
                 leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)]


def verylongfermata(leaf: Leaf) -> List[Leaf]:
    return [Leaf(leaf.duration * Ratio(8, 2),
                 leaf.pitches, leaf.key, leaf.volume, leaf.articulation, leaf.ties)]


# -----------------------------
# Ornament dispatch table
# -----------------------------

ornament_function_map: Dict[str, Callable[[Leaf], List[Leaf]]] = {
    "prall": prall,
    "prallup": prallup,
    "pralldown": pralldown,
    "upprall": upprall,
    "downprall": downprall,
    "prallprall": prallprall,
    "lineprall": lineprall,
    "prallmordent": prallmordent,
    "mordent": mordent,
    "upmordent": upmordent,
    "downmordent": downmordent,
    "trill": trill,
    "turn": turn,
    "reverseturn": reverseturn,
    "shortfermata": shortfermata,
    "fermata": fermata,
    "longfermata": longfermata,
    "verylongfermata": verylongfermata,
}
