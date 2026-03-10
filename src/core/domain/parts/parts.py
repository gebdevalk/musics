# parts.py
from abc import ABC, abstractmethod
from bisect import bisect_right
from collections import UserList
from typing import List, Tuple, TypeVar, Literal

"""
Here is a list of opposites that express the concepts of simultaneous (occurring at the same time) versus serial (occurring one after another), followed by common synonyms for each.

The Opposites: Simultaneous vs. Serial

Simultaneous (Parallel) Serial (Sequential)
Concurrent Consecutive
Synchronous Asynchronous
Parallel Sequential / Linear
Coinciding Staggered
Together One by one
At the same time In succession / Back-to-back
In parallel In series

---

Synonyms for "Simultaneous"

These words describe things happening or operating at the same time.

· Concurrent: Existing, happening, or done at the same time.
  · Example: "The company is working on two concurrent software projects."
· Synchronous: Existing or occurring at the same time; working in unison.
  · Example: "The dancers performed a perfectly synchronous routine."
· Parallel: Occurring or existing at the same time or in a similar way; analogous.
  · Example: "The rise of the internet saw a parallel increase in online shopping."
· Coincident / Coinciding: Occurring together in space or time.
  · Example: "His arrival was coincident with the start of the party."
· Contemporaneous: Existing or occurring in the same period of time. (Often used for history).
  · Example: "The development of the printing press was contemporaneous with the Age of Exploration."
· Coeval: Having the same age or date of origin; contemporary.
  · Example: "The two manuscripts are roughly coeval."
· Simultaneity (n.) / Instantaneous (adj.): The quality of happening at the exact same moment / occurring or done instantly.
  · Example: "There was a simultaneous flash of lightning and crack of thunder."

Synonyms for "Serial"

These words describe things happening or arranged in a sequence, one after another.

· Sequential: Forming or following in a logical order or sequence.
  · Example: "The domain is processed in sequential order."
· Consecutive: Following one after another without an interruption.
  · Example: "It rained for seven consecutive days."
· Successive: Following one another or following others.
  · Example: "The team has won five successive games."
· Intermittent: While not strictly "serial," it is often contrasted with "continuous simultaneous." It means stopping and starting at intervals (occurring in a broken sequence).
  · Example: "The signal was intermittent."
· Staggered: Arranged so that things are not aligned or do not happen at the same time (the opposite of synchronous).
  · Example: "The students have staggered lunch hours to avoid crowding."
· Linear: Proceeding in a straight, sequential manner.
  · Example: "Human speech is a linear stream of sound."
· One after another / In turn: Phrases describing serial action.
  · Example: "They entered the room one after another."
"""

T = TypeVar('T')  # Generic type for point values
InterpType = Literal["step", "linear"]

class Parent(ABC):
    def __init__(self, duration: float):
        if duration < 0:
            raise ValueError("Duration cannot be negative")
        self.duration = duration

    @property
    def duration(self) -> float:
        return self._duration

    @duration.setter
    def duration(self, value: float) -> None:
        if value < 0:
            raise ValueError("Duration cannot be negative")
        self._duration = value


class Envelope(Parent, UserList):
    def __init__(self, duration: float, data: List[Tuple[float, float]] = None):
        Parent.__init__(self, duration)
        UserList.__init__(self)

        if data:
            self.data = data
        else:
            self.data = []

    def add(self, time: float, value: float, type: InterpType = "step") -> None:
        """Add a point to the envelope with interpolation type"""
        self.data.append((time, value, type))
        self.data.sort(key=lambda x: x[0])

    def value_at(self, t: float) -> T:
        return interpolate_at(self, t)

    def reverse(self):
        super().reverse()

    def __str__(self) -> str:
        return f"Envelope(duration={self.duration}, points={len(self.data)})"

class Context(Parent):
    ...



def interpolate_at(self, t: float) -> T:
    if not self.data:
        return None

    # Before first point
    if t < 0 or t < self.data[0][0]:
        return None

    # After last point
    if t >= self.data[-1][0]:
        return self.data[-1][1]

    # Find interval
    idx = bisect_right(self.data, (t, float("inf") if isinstance(self.data[0][1], (int, float)) else t, None))
    t0, v0, interp0 = self.data[idx - 1]
    t1, v1, _ = self.data[idx]

    # Determine interpolation type
    interp = interp0 or "step"

    if interp == "step":
        return v0
    elif interp == "linear":
        return _interpolate_linear(t0, v0, t1, v1, t)
    else:
        raise ValueError(f"Unknown interpolation type: {interp}")


def _interpolate_linear(t0: float, v0: T, t1: float, v1: T, t: float) -> T:
    """
    Linear interpolation between two values.
    For custom types, you may need to override this method or provide
    custom interpolation functions.
    """
    alpha = (t - t0) / (t1 - t0)

    # Handle common numeric types
    if isinstance(v0, (int, float)) and isinstance(v1, (int, float)):
        return v0 + alpha * (v1 - v0)  # type: ignore

    # Handle tuples/lists of numbers (e.g., for RGB colors, positions)
    if isinstance(v0, (tuple, list)) and isinstance(v1, (tuple, list)) and len(v0) == len(v1):
        if all(isinstance(x, (int, float)) for x in v0) and all(isinstance(x, (int, float)) for x in v1):
            result = [v0[i] + alpha * (v1[i] - v0[i]) for i in range(len(v0))]
            return type(v0)(result)  # Preserve the original type (tuple/list)

    raise TypeError(f"Linear interpolation not supported for type {type(v0)}. "
                    f"Consider subclassing and overriding _interpolate_linear")
