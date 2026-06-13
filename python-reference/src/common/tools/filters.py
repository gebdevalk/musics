
from typing import Iterable, List, Callable, TypeVar
import numpy as np

T = TypeVar("T") # musical token type


# ------------------------------------------------------------
# Basic Filter Type
# ------------------------------------------------------------

Filter = Callable[[Iterable[T]], List[T]]


# ------------------------------------------------------------
# Low-pass / High-pass Filters (Symbolic)
# ------------------------------------------------------------

def low_pass(limit: float, key: Callable[[T], float] = lambda x: x) -> Filter:
    """
    Keep elements whose key(x) <= limit.
    Works for pitches, durations, velocities, etc.
    """
    def apply(seq: Iterable[T]) -> List[T]:
        return [x for x in seq if key(x) <= limit]
    return apply


def high_pass(limit: float, key: Callable[[T], float] = lambda x: x) -> Filter:
    """
    Keep elements whose key(x) >= limit.
    """
    def apply(seq: Iterable[T]) -> List[T]:
        return [x for x in seq if key(x) >= limit]
    return apply


# ------------------------------------------------------------
# Window Filters (Symbolic)
# ------------------------------------------------------------

def window(start: float, end: float) -> Filter:
    """
    Slice a sequence by fractional positions.
    start, end are between 0 and 1.
    """
    def apply(seq: Iterable[T]) -> List[T]:
        seq = list(seq)
        n = len(seq)
        a = int(start * n)
        b = int(end * n)
        return seq[a:b]
    return apply


def attribute_window(min_val: float,
                     max_val: float,
                     key: Callable[[T], float] = lambda x: x) -> Filter:
    """
    Keep elements whose attribute lies between min_val and max_val.
    """
    def apply(seq: Iterable[T]) -> List[T]:
        return [x for x in seq if min_val <= key(x) <= max_val]
    return apply


# ------------------------------------------------------------
# Weighted Window (for numeric sequences)
# ------------------------------------------------------------

def weighted_window(start: float,
                    end: float,
                    shape: str = "hann") -> Filter:
    """
    Apply a multiplicative window to numeric sequences.
    Useful for shaping dynamics, weights, probabilities, etc.
    """
    def apply(seq: Iterable[float]) -> List[float]:
        seq = list(seq)
        n = len(seq)
        a = int(start * n)
        b = int(end * n)

        if b <= a:
            return seq # empty or invalid window

        if shape == "hann":
            w = 0.5 - 0.5 * np.cos(2 * np.pi * np.linspace(0, 1, b - a))
        elif shape == "linear":
            w = np.linspace(0, 1, b - a)
        else:
            raise ValueError(f"Unknown window shape: {shape}")

        out = seq.copy()
        for i, weight in zip(range(a, b), w):
            out[i] = out[i] * weight

        return out

    return apply


# ------------------------------------------------------------
# Filter Composition
# ------------------------------------------------------------

def chain(*filters: Filter) -> Filter:
    """
    Compose multiple filters into a single transformation.
    """
    def apply(seq: Iterable[T]) -> List[T]:
        for f in filters:
            seq = f(seq)
        return seq
    return apply


# ------------------------------------------------------------
# Examples
# ------------------------------------------------------------

if __name__ == "__main__":

    # Example 1: Low-pass on numeric pitches
    lp = low_pass(70)
    print(lp([60, 72, 55, 80, 65]))
    # -> [60, 55, 65]

    # Example 2: Low-pass on tuple notes (pitch, duration)
    notes = [(60, 1.0), (72, 0.5), (55, 2.0)]
    lp_pitch = low_pass(65, key=lambda n: n[0])
    print(lp_pitch(notes))
    # -> [(60, 1.0), (55, 2.0)]

    # Example 3: Window the middle 50% of a sequence
    mid = window(0.25, 0.75)
    print(mid([1, 2, 3, 4, 5, 6, 7, 8]))
    # -> [3, 4, 5, 6]

    # Example 4: Attribute window on duration
    class Note:
        def __init__(self, pitch, dur, vel):
            self.pitch = pitch
            self.dur = dur
            self.vel = vel
        def __repr__(self):
            return f"Note({self.pitch}, {self.dur}, {self.vel})"

    seq = [Note(60, 0.5, 90), Note(62, 1.2, 70), Note(65, 2.0, 80)]
    dur_win = attribute_window(1.0, 2.0, key=lambda n: n.dur)
    print(dur_win(seq))
    # -> [Note(62, 1.2, 70), Note(65, 2.0, 80)]

    # Example 5: Weighted window on numeric values
    env = weighted_window(0.1, 0.9, shape="hann")
    print(env([1, 1, 1, 1, 1, 1, 1, 1]))
    # -> windowed values

    # Example 6: Compose filters
    pipeline = chain(
        low_pass(70),
        window(0.2, 0.8),
        high_pass(60)
    )
    print(pipeline([55, 60, 62, 65, 72, 75, 58, 61]))
    # -> filtered sequence
