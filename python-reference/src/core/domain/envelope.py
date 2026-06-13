from __future__ import annotations

import bisect
from enum import Enum
from typing import NamedTuple, List, Any

# algo: Optional[Union[str, ConstantRef, Envelope]] = None  # algorithm
# a: Optional[Union[float, ConstantRef, Envelope]] = None  # articulation (0-2)
# c: Optional[Union[tuple, ConstantRef, Envelope]] = None  # color (r,g,b)
# d: Optional[Union[float, ConstantRef, Envelope]] = None  # density (0-1)
# k: Optional[Union[tuple, ConstantRef, Envelope]] = None  # key + accidental
# o: Optional[Union[int, Envelope]] = None  # octave (0-8)
# p: Optional[Union[float, ConstantRef, Envelope]] = None  # panning (-1 to 1)
# r: Optional[Union[int, Envelope]] = None  # repeat count
# s: Optional[Union[float, ConstantRef, Envelope]] = None  # scale (0-1)
# t: Optional[Union[int, Envelope]] = None  # tempo (bpm)
# times: Optional[Union[int, Envelope]] = None  # number of times (0-100)
# v: Optional[Union[int, float, ConstantRef, Envelope]] = None  # volume (0-100)
# midi: Optional[Union[int, ConstantRef, Envelope]] = None  # midi note (0-127)

# ============================================================
# Interpolation Types (minimal musical set)
# ============================================================

class IP(Enum):
    FIXED = "fixed"
    STEP = "step"
    LINEAR_UP = "lin_up"
    LINEAR_DOWN = "lin_down"
    SMOOTH = "smooth"
    EASE_IN = "ease_in"
    EASE_OUT = "ease_out"
    EASE_IN_OUT = "ease_in_out"

    @staticmethod
    def easing(ip: "IP"):
        if ip is IP.FIXED:
            return lambda t: 0.0
        if ip is IP.STEP:
            return lambda t: 0.0 if t < 1.0 else 1.0
        if ip in (IP.LINEAR_UP, IP.LINEAR_DOWN):
            return lambda t: t
        if ip is IP.SMOOTH:
            return lambda t: t * t * (3 - 2 * t)
        if ip is IP.EASE_IN:
            return lambda t: t * t
        if ip is IP.EASE_OUT:
            return lambda t: 1 - (1 - t) * (1 - t)
        if ip is IP.EASE_IN_OUT:
            return lambda t: 2 * t * t if t < 0.5 else 1 - ((-2 * t + 2) ** 2) / 2
        raise ValueError(f"Unsupported IP: {ip}")


# ============================================================
# Point
# ============================================================

class Point(NamedTuple):
    time: float
    value: Any
    ip: IP

    @staticmethod
    def from_dict(d: dict) -> "Point":
        return Point(
            time=d["time"],
            value=d["value"],
            ip=IP(d["ip"])
        )


# ============================================================
# Envelope (mutable, each instance owns its points list)
# ============================================================

class Envelope:
    __slots__ = ('points',)

    def __init__(self, points: List[Point] | None = None) -> None:
        self.points: List[Point] = list(points) if points else []

    @staticmethod
    def empty() -> "Envelope":
        return Envelope(points=[])

    @staticmethod
    def from_dict(data: List[dict]) -> "Envelope":
        pts = [Point.from_dict(d) for d in data]
        return Envelope(points=pts)

    @property
    def is_empty(self) -> bool:
        return len(self.points) == 0

    @property
    def duration(self) -> float:
        return self.points[-1].time if self.points else 0.0

    # ------------------------------------------------------------
    # Append returns a NEW Envelope (immutable)
    # ------------------------------------------------------------
    def append(self, time: float, value: Any, ip: IP) -> None:
        if self.points and time < self.points[-1].time:
            raise ValueError("Time must be non-decreasing")

        if self.points and self.points[-1].time == time:
            self.points[-1] = Point(time, value, ip)
        else:
            self.points.append(Point(time, value, ip))

    # ------------------------------------------------------------
    # Sampling
    # ------------------------------------------------------------
    def get(self, time: float):
        if not self.points:
            return None

        # Clamp after last
        if time >= self.points[-1].time:
            return self.points[-1].value

        # Clamp before first
        if time <= self.points[0].time:
            return self.points[0].value

        # Find segment
        times = [p.time for p in self.points]
        idx = bisect.bisect_right(times, time) - 1
        prev, nxt = self.points[idx], self.points[idx + 1]

        # Constant segments
        if nxt.ip in (IP.FIXED, IP.STEP):
            return prev.value

        # Interpolated
        t = (time - prev.time) / (nxt.time - prev.time)
        eased = IP.easing(nxt.ip)(t)

        # Numeric interpolation only
        if isinstance(prev.value, (int, float)) and isinstance(nxt.value, (int, float)):
            return (1 - eased) * prev.value + eased * nxt.value

        # Non-numeric → no interpolation
        return prev.value

    # ------------------------------------------------------------
    # Reversal (restored)
    # ------------------------------------------------------------
    def reverse(self) -> "Envelope":
        if not self.points:
            return self

        duration = self.duration
        pts = self.points

        rev = list(reversed(pts))
        new_points = []

        for i, p in enumerate(rev):
            if i == len(rev) - 1:
                ip = IP.FIXED
            else:
                ip = pts[len(pts) - 2 - i].ip
            new_points.append(Point(duration - p.time, p.value, ip))

        return Envelope(points=new_points)
