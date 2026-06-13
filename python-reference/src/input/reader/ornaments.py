from __future__ import annotations

from fractions import Fraction
from typing import List, Callable, Dict, Any


# ============================================================
# Ornaments as symbolic transformations
# ============================================================

# The % 6 bug in up() is a separate issue — it should be % 7 since there are 7 diatonic notes.
# But up() is only used by upper()/lower() for ornament construction, not for relative pitch parsing,
# so it doesn't affect the transformer. Worth fixing in the ornament code though.

def ornamented(leaf: tuple, ornament: str, scale: Any) -> List[tuple]:
    """
    Apply an ornament to a tuple using the given scale.

    - leaf:     the base tuple to ornament
    - ornament: string name (e.g. "\\prall", "trill")
    - scale:    object providing .upper(pitch) and .lower(pitch)
    """
    token, dur, articulation, pitches, dynamic, tie, ornament = leaf
    ornament_type = ornament.replace("\\", "")
    func = ornament_function_map.get(ornament_type, plain)
    result = func(leaf, scale)
    result[0].id = leaf.id
    return result


def plain(leaf: tuple, scale: Any) -> List[tuple]:
    """No ornament."""
    return [leaf]


# ============================================================
# Ornament implementations
# ============================================================

def prall(leaf: tuple, scale: Any) -> List[tuple]:
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf 
    value8 = duFractionn / 8
    return [
        ("", value8, pitches, dynamic, tie,),
        ("" ,value8, (scale.upper(p) for p in pitches), dynamic, tie,),
        (token, duFractionn * Fraction(3, 4),  pitches, dynamic, tie,)
    ]


def prallup(leaf: tuple, scale: Any) -> List[tuple]:
    return upprall(leaf,scale)


def pralldown(leaf: tuple, scale: Any) -> List[tuple]:
    return downprall(leaf, scale)


def upprall(leaf: tuple, scale: Any) -> List[tuple]:
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    value8 = leaf.duFractionn / 8
    high = ("", value8, (scale.upper(p) for p in pitches), dynamic, tie,)
    base = ("", value8, pitches, dynamic, tie,)
    low  = ("", value8, (scale.lower(p) for p in pitches), dynamic, tie,)
    
    return [low, base, high, base, high, base, high, base]


def downprall(leaf: tuple, scale: Any) -> List[tuple]:
    value8 = leaf.duFractionn / 8
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    high = ("", value8, (scale.upper(p) for p in pitches), dynamic, tie,)
    base = ("", value8, pitches, dynamic, tie,)
    low  = ("", value8, (scale.lower(p) for p in pitches), dynamic, tie,)
    return [high, base, low, base, high, base, high, base]


def prallprall(leaf: tuple, scale: Any) -> List[tuple]:
    value8 = leaf.duFractionn / 8
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    high = ("", value8, (scale.upper(p) for p in pitches), dynamic, tie,)
    base = ("", value8, pitches, dynamic, tie,)
    return [
        base, high, base, high,
        ("", leaf.duFractionn * Fraction(1, 2), pitches, dynamic, tie,),
    ]


def lineprall(leaf: tuple, scale: Any) -> List[tuple]:
    value16 = leaf.duFractionn / 16
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    high = ("", value16, (scale.upper(p) for p in pitches), dynamic, tie,)
    base = ("", value16, pitches, dynamic, tie,)
    return [
        ("", leaf.duFractionn / 2, (scale.upper(p) for p in pitches), dynamic, tie,),
        base, high, base, high,
        ("", leaf.duFractionn / 4, pitches, dynamic, tie,),
    ]


def prallmordent(leaf: tuple, scale: Any) -> List[tuple]:
    value8 = leaf.duFractionn / 8
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    high = ("", value8, (scale.upper(p) for p in pitches), dynamic, tie,)
    base = ("", value8, pitches, dynamic, tie,)
    low  = ("", value8, (scale.lower(p) for p in pitches), dynamic, tie,)
    return [high, base, high, base, high, base, low, base]


def mordent(leaf: tuple, scale: Any) -> List[tuple]:
    value8 = leaf.duFractionn / 8
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    return [
        ("", value8, pitches, dynamic, tie,),
        ("", value8, (scale.lower(p) for p in pitches), dynamic, tie,),
        ("", leaf.duFractionn * Fraction(3, 4), pitches, dynamic, tie,),
    ]


def upmordent(leaf: tuple, scale: Any) -> List[tuple]:
    value8 = leaf.duFractionn / 8
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    high = ("", value8, (scale.upper(p) for p in pitches), dynamic, tie,)
    base = ("", value8, pitches, dynamic, tie,)
    low  = ("", value8, (scale.lower(p) for p in pitches), dynamic, tie,)
    return [low, base, high, base, high, base, low, base]


def downmordent(leaf: tuple, scale: Any) -> List[tuple]:
    value8 = leaf.duFractionn / 12
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    high = ("", value8, (scale.upper(p) for p in pitches), dynamic, tie,)
    base = ("", value8, pitches, dynamic, tie,)
    low  = ("", value8, (scale.lower(p) for p in pitches), dynamic, tie,)
    return [
        high, base, low, base,
        high, base, high, base,
        high, base, low, base,
    ]


def trill(leaf: tuple, scale: Any) -> List[tuple]:
    value8 = leaf.duFractionn / 8
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    high = ("", value8, (scale.upper(p) for p in pitches), dynamic, tie,)
    base = ("", value8, pitches, dynamic, tie,)
    return [
        high, base, high, base, high,
        ("", leaf.duFractionn * Fraction(3, 8), pitches, dynamic, tie,),
    ]


def turn(leaf: tuple, scale: Any) -> List[tuple]:
    value4 = leaf.duFractionn / 4
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    base = ("", value4, pitches, dynamic, tie,)
    return [
        ("", value4, (scale.upper(p) for p in pitches), dynamic, tie,),
        base,
        ("", value4, (scale.lower(p) for p in pitches), dynamic, tie,),
        base,
    ]


def reverseturn(leaf: tuple, scale: Any) -> List[tuple]:
    value4 = leaf.duFractionn / 4
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    base = ("", value4, pitches, dynamic, tie,)
    return [
        ("", value4, (scale.lower(p) for p in pitches), dynamic, tie,),
        base,
        ("", value4, (scale.upper(p) for p in pitches), dynamic, tie,),
        base,
    ]


def shortfermata(leaf: tuple, scale: Any) -> List[tuple]:
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    return [("", leaf.duFractionn * Fraction(3, 2), pitches, dynamic, tie,)]


def fermata(leaf: tuple, scale: Any) -> List[tuple]:
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    return [("", leaf.duFractionn * Fraction(4, 2), pitches, dynamic, tie,)]


def longfermata(leaf: tuple, scale: Any) -> List[tuple]:
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    return [("", leaf.duFractionn * Fraction(6, 2), pitches, dynamic, tie,)]


def verylongfermata(leaf: tuple, scale: Any) -> List[tuple]:
    token, duFractionn, articulation, pitches, dynamic, tie, ornament = leaf
    return [("", leaf.duFractionn * Fraction(8, 2), pitches, dynamic, tie,)]


# ============================================================
# Dispatch table
# ============================================================

ornament_function_map: Dict[str, Callable[[tuple, Any], List[tuple]]] = {
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
