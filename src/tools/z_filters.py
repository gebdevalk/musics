
"""
z_filters.py

Symbolic recurrence filters for algorithmic composition.
Implements:
- generic z-filter engine
- smoothing filters
- momentum filters
- decay/memory filters
- interval-based recurrence
- pitch-class recurrence
- duration smoothing

Supports both pure functional and in-place mutation via set_value.
Always returns a new list for compositional safety.
"""

from typing import Iterable, List, Callable, TypeVar

T = TypeVar("T") # musical token type
Filter = Callable[[Iterable[T]], List[T]]


# ------------------------------------------------------------
# Core z-filter engine
# ------------------------------------------------------------

def z_filter(
    b: List[float],
    a: List[float],
    key: Callable[[T], float] = lambda x: x,
    set_value: Callable[[T, float], T] = None
) -> Filter:
    """
    Generic symbolic z-filter.
    b: numerator coefficients
    a: denominator coefficients (a[0] must be 1)
    key: extract numeric value from element
    set_value: write numeric value back into element
               - if None: assume numeric sequence
               - if provided: may mutate or return new object

    Always returns a new list.
    """

    if a[0] != 1:
        raise ValueError("a[0] must be 1")

    def apply(seq: Iterable[T]) -> List[T]:
        seq = list(seq)
        x = [key(s) for s in seq]
        y = [0.0] * len(x)

        for n in range(len(x)):
            # feedforward
            y[n] = sum(
                b[k] * x[n - k] if n - k >= 0 else 0
                for k in range(len(b))
            )
            # feedback
            y[n] -= sum(
                a[k] * y[n - k] if n - k >= 0 else 0
                for k in range(1, len(a))
            )

        # write back
        if set_value is None:
            # numeric mode
            return y
        else:
            # object mode
            return [set_value(s, v) for s, v in zip(seq, y)]

    return apply


# ------------------------------------------------------------
# Convenience constructors
# ------------------------------------------------------------

def smooth(alpha: float, key=lambda x: x, set_value=None) -> Filter:
    """
    One-pole smoothing filter:
    y[n] = (1-alpha)*x[n] + alpha*y[n-1]
    """
    b = [1 - alpha]
    a = [1, -alpha]
    return z_filter(b, a, key, set_value)


def momentum(beta: float, key=lambda x: x, set_value=None) -> Filter:
    """
    Momentum / inertia filter:
    y[n] = x[n] + beta*(y[n-1] - x[n-1])
    """
    # Derived recurrence:
    # y[n] = (1+beta)*x[n] - beta*x[n-1] + beta*y[n-1]
    b = [1 + beta, -beta]
    a = [1, -beta]
    return z_filter(b, a, key, set_value)


def memory(decay: float, key=lambda x: x, set_value=None) -> Filter:
    """
    Decay/memory filter:
    y[n] = x[n] + decay*y[n-1]
    """
    b = [1]
    a = [1, -decay]
    return z_filter(b, a, key, set_value)


# ------------------------------------------------------------
# Interval-based recurrence
# ------------------------------------------------------------

def smooth_intervals(alpha: float, key=lambda x: x, set_value=None) -> Filter:
    """
    Smooth melodic intervals instead of absolute values.
    """
    def apply(seq: Iterable[T]) -> List[T]:
        seq = list(seq)
        if len(seq) < 2:
            return seq

        # extract pitches
        vals = [key(s) for s in seq]
        intervals = [vals[i+1] - vals[i] for i in range(len(vals)-1)]

        # smooth intervals
        smoothed = smooth(alpha)(intervals)

        # reconstruct contour
        out_vals = [vals[0]]
        for i in range(len(smoothed)):
            out_vals.append(out_vals[-1] + smoothed[i])

        # write back
        if set_value is None:
            return out_vals
        else:
            return [set_value(s, v) for s, v in zip(seq, out_vals)]

    return apply


def interval_gain(factor: float, key=lambda x: x, set_value=None) -> Filter:
    """
    Multiply intervals by a factor (exaggerate or compress contour).
    """
    def apply(seq: Iterable[T]) -> List[T]:
        seq = list(seq)
        if len(seq) < 2:
            return seq

        vals = [key(s) for s in seq]
        intervals = [vals[i+1] - vals[i] for i in range(len(vals)-1)]
        scaled = [i * factor for i in intervals]

        out_vals = [vals[0]]
        for i in scaled:
            out_vals.append(out_vals[-1] + i)

        if set_value is None:
            return out_vals
        else:
            return [set_value(s, v) for s, v in zip(seq, out_vals)]

    return apply


# ------------------------------------------------------------
# Pitch-class recurrence
# ------------------------------------------------------------

def pc_smooth(alpha: float, key=lambda x: x, set_value=None) -> Filter:
    """
    Smooth pitch classes modulo 12.
    """
    def apply(seq: Iterable[T]) -> List[T]:
        seq = list(seq)
        pcs = [key(s) % 12 for s in seq]
        smoothed = smooth(alpha)(pcs)

        if set_value is None:
            return smoothed
        else:
            return [set_value(s, v) for s, v in zip(seq, smoothed)]

    return apply


# ------------------------------------------------------------
# Duration smoothing
# ------------------------------------------------------------

def smooth_duration(alpha: float, key=lambda x: x, set_value=None) -> Filter:
    """
    Smooth durations or IOIs.
    """
    return smooth(alpha, key, set_value)


# ------------------------------------------------------------
# Optional self-test
# ------------------------------------------------------------

if __name__ == "__main__":
    print("Smooth:", smooth(0.7)([1, 2, 3, 10, 20]))
    print("Momentum:", momentum(0.5)([1, 2, 3, 10, 20]))
    print("Memory:", memory(0.3)([1, 2, 3, 10, 20]))
    print("Interval smooth:", smooth_intervals(0.5)([60, 62, 65, 70]))
    print("Interval gain:", interval_gain(2.0)([60, 62, 65, 70]))
    print("PC smooth:", pc_smooth(0.7)([60, 61, 62, 63, 64]))
