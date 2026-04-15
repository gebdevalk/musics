from __future__ import annotations

from typing import List, Callable, Dict, Any

from core.domain.leafs import Leaf
from tools.ratio import Ratio


# ============================================================
# Ornaments as symbolic transformations
# ============================================================

def ornamented(leaf: Leaf, ornament: str, scale: Any) -> List[Leaf]:
    """
    Apply an ornament to a Leaf using the given scale.

    - leaf:     the base Leaf to ornament
    - ornament: string name (e.g. "\\prall", "trill")
    - scale:    object providing .upper(pitch) and .lower(pitch)
    """
    ornament_type = ornament.replace("\\", "")
    func = ornament_function_map.get(ornament_type, plain)
    return func(leaf, scale)


def plain(leaf: Leaf, scale: Any) -> List[Leaf]:
    """No ornament."""
    return [leaf]


# ------------------------------------------------------------
# Helper: expressive parameter preservation
# ------------------------------------------------------------

def _base_kwargs(leaf: Leaf) -> dict:
    """
    Preserve expressive overrides and context.
    Context-based resolution happens later in the performer.
    """
    return dict(
        dynamic=leaf.dynamic,
        articulation=leaf.articulation,
        timbre=leaf.timbre,
        tied=leaf.tied,
        context=leaf.context,
    )


# ============================================================
# Ornament implementations
# ============================================================

def prall(leaf: Leaf, scale: Any) -> List[Leaf]:
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    return [
        Leaf(duration=value8, pitches=leaf.pitches, **kw),
        Leaf(duration=value8, pitches=[scale.upper(p) for p in leaf.pitches], **kw),
        Leaf(duration=leaf.duration * Ratio(3, 4), pitches=leaf.pitches, **kw),
    ]


def prallup(leaf: Leaf, scale: Any) -> List[Leaf]:
    return [leaf]


def pralldown(leaf: Leaf, scale: Any) -> List[Leaf]:
    return [leaf]


def upprall(leaf: Leaf, scale: Any) -> List[Leaf]:
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[scale.lower(p) for p in leaf.pitches], **kw)
    return [low, base, high, base, high, base, high, base]


def downprall(leaf: Leaf, scale: Any) -> List[Leaf]:
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[scale.lower(p) for p in leaf.pitches], **kw)
    return [high, base, low, base, high, base, high, base]


def prallprall(leaf: Leaf, scale: Any) -> List[Leaf]:
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    return [
        base, high, base, high,
        Leaf(duration=leaf.duration * Ratio(1, 2), pitches=leaf.pitches, **kw),
    ]


def lineprall(leaf: Leaf, scale: Any) -> List[Leaf]:
    value16 = leaf.duration / 16
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value16, pitches=[scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value16, pitches=leaf.pitches, **kw)
    return [
        Leaf(duration=leaf.duration / 2, pitches=[scale.upper(p) for p in leaf.pitches], **kw),
        base, high, base, high,
        Leaf(duration=leaf.duration / 4, pitches=leaf.pitches, **kw),
    ]


def prallmordent(leaf: Leaf, scale: Any) -> List[Leaf]:
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[scale.lower(p) for p in leaf.pitches], **kw)
    return [high, base, high, base, high, base, low, base]


def mordent(leaf: Leaf, scale: Any) -> List[Leaf]:
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    return [
        Leaf(duration=value8, pitches=leaf.pitches, **kw),
        Leaf(duration=value8, pitches=[scale.lower(p) for p in leaf.pitches], **kw),
        Leaf(duration=leaf.duration * Ratio(3, 4), pitches=leaf.pitches, **kw),
    ]


def upmordent(leaf: Leaf, scale: Any) -> List[Leaf]:
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[scale.lower(p) for p in leaf.pitches], **kw)
    return [low, base, high, base, high, base, low, base]


def downmordent(leaf: Leaf, scale: Any) -> List[Leaf]:
    value8 = leaf.duration / 12
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    low  = Leaf(duration=value8, pitches=[scale.lower(p) for p in leaf.pitches], **kw)
    return [
        high, base, low, base,
        high, base, high, base,
        high, base, low, base,
    ]


def trill(leaf: Leaf, scale: Any) -> List[Leaf]:
    value8 = leaf.duration / 8
    kw = _base_kwargs(leaf)
    high = Leaf(duration=value8, pitches=[scale.upper(p) for p in leaf.pitches], **kw)
    base = Leaf(duration=value8, pitches=leaf.pitches, **kw)
    return [
        high, base, high, base, high,
        Leaf(duration=leaf.duration * Ratio(3, 8), pitches=leaf.pitches, **kw),
    ]


def turn(leaf: Leaf, scale: Any) -> List[Leaf]:
    value4 = leaf.duration / 4
    kw = _base_kwargs(leaf)
    base = Leaf(duration=value4, pitches=leaf.pitches, **kw)
    return [
        Leaf(duration=value4, pitches=[scale.upper(p) for p in leaf.pitches], **kw),
        base,
        Leaf(duration=value4, pitches=[scale.lower(p) for p in leaf.pitches], **kw),
        base,
    ]


def reverseturn(leaf: Leaf, scale: Any) -> List[Leaf]:
    value4 = leaf.duration / 4
    kw = _base_kwargs(leaf)
    base = Leaf(duration=value4, pitches=leaf.pitches, **kw)
    return [
        Leaf(duration=value4, pitches=[scale.lower(p) for p in leaf.pitches], **kw),
        base,
        Leaf(duration=value4, pitches=[scale.upper(p) for p in leaf.pitches], **kw),
        base,
    ]


def shortfermata(leaf: Leaf, scale: Any) -> List[Leaf]:
    kw = _base_kwargs(leaf)
    return [Leaf(duration=leaf.duration * Ratio(3, 2), pitches=leaf.pitches, **kw)]


def fermata(leaf: Leaf, scale: Any) -> List[Leaf]:
    kw = _base_kwargs(leaf)
    return [Leaf(duration=leaf.duration * Ratio(4, 2), pitches=leaf.pitches, **kw)]


def longfermata(leaf: Leaf, scale: Any) -> List[Leaf]:
    kw = _base_kwargs(leaf)
    return [Leaf(duration=leaf.duration * Ratio(6, 2), pitches=leaf.pitches, **kw)]


def verylongfermata(leaf: Leaf, scale: Any) -> List[Leaf]:
    kw = _base_kwargs(leaf)
    return [Leaf(duration=leaf.duration * Ratio(8, 2), pitches=leaf.pitches, **kw)]


# ============================================================
# Dispatch table
# ============================================================

ornament_function_map: Dict[str, Callable[[Leaf, Any], List[Leaf]]] = {
    "prall":           prall,
    "prallup":         prallup,
    "pralldown":       pralldown,
    "upprall":         upprall,
    "downprall":       downprall,
    "prallprall":      prallprall,
    "lineprall":       lineprall,
    "prallmordent":    prallmordent,
    "mordent":         mordent,
    "upmordent":       upmordent,
    "downmordent":     downmordent,
    "trill":           trill,
    "turn":            turn,
    "reverseturn":     reverseturn,
    "shortfermata":    shortfermata,
    "fermata":         fermata,
    "longfermata":     longfermata,
    "verylongfermata": verylongfermata,
}
