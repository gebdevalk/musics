# point_envelope.py

from __future__ import annotations

import bisect
import json
import math
from enum import Enum
from typing import Generic, TypeVar, List, Optional, Tuple, Callable

T = TypeVar('T')


# ============================================================
# Interpolation Type
# ============================================================

class IP(Enum):
    """
    Interpolation types for envelope segments.

    Each Point carries an IP value describing how the segment
    *starting at that point* should interpolate toward the next point.

    FIXED and STEP behave as constant segments.
    Other types define easing curves or special shapes.

    The `reversed` property returns the interpolation type that should
    be used when the envelope is reversed in time.
    """

    FIXED = "fixed"
    LINEAR_UP = "lin_up"
    LINEAR_DOWN = "lin_down"
    SMOOTH = "smooth"
    EASE_IN = "ease_in"
    EASE_OUT = "ease_out"
    EASE_IN_OUT = "ease_in_out"
    STEP = "step"
    BOUNCE = "bounce"
    ELASTIC = "elastic"
    SINE = "sine"
    EXPONENTIAL = "exponential"
    LOGARITHMIC = "logarithmic"
    SQUARE_ROOT = "square_root"
    CUBIC_ROOT = "cubic_root"

    @property
    def reversed(self) -> 'IP':
        """
        Return the interpolation type appropriate when time is reversed.

        Only directional types swap; FIXED, STEP, SMOOTH, etc. remain unchanged.
        """
        reverse_map = {
            IP.LINEAR_UP: IP.LINEAR_DOWN,
            IP.LINEAR_DOWN: IP.LINEAR_UP,
            IP.EASE_IN: IP.EASE_OUT,
            IP.EASE_OUT: IP.EASE_IN,
            IP.EXPONENTIAL: IP.LOGARITHMIC,
            IP.LOGARITHMIC: IP.EXPONENTIAL,
            IP.SQUARE_ROOT: IP.CUBIC_ROOT,
            IP.CUBIC_ROOT: IP.SQUARE_ROOT,
        }
        return reverse_map.get(self, self)

    @staticmethod
    def easing_function(ip_type: 'IP') -> Callable[[float], float]:
        """
        Return a function f(t) → eased_t for interpolation.

        t is always in [0, 1].
        """
        functions = {
            IP.FIXED:       lambda t: 0.0,
            IP.LINEAR_UP:   lambda t: t,
            IP.LINEAR_DOWN: lambda t: t,
            IP.SMOOTH:      lambda t: t * t * (3 - 2 * t),
            IP.EASE_IN:     lambda t: t * t,
            IP.EASE_OUT:    lambda t: 1 - (1 - t) * (1 - t),
            IP.EASE_IN_OUT: lambda t: 2 * t * t if t < 0.5 else 1 - pow(-2 * t + 2, 2) / 2,
            IP.STEP:        lambda t: 0.0 if t < 1.0 else 1.0,
            IP.BOUNCE:      IP._bounce_easing,
            IP.ELASTIC:     IP._elastic_easing,
            IP.SINE:        lambda t: (1 - math.cos(t * math.pi)) / 2,
            IP.EXPONENTIAL: lambda t: math.pow(2, 10 * (t - 1)),
            IP.LOGARITHMIC: lambda t: math.log(t * 9 + 1, 10),
            IP.SQUARE_ROOT: lambda t: math.sqrt(t),
            IP.CUBIC_ROOT:  lambda t: math.pow(t, 1 / 3),
        }
        return functions.get(ip_type)

    @staticmethod
    def _bounce_easing(t: float) -> float:
        """Bounce easing curve."""
        if t < 1 / 2.75:
            return 7.5625 * t * t
        elif t < 2 / 2.75:
            t -= 1.5 / 2.75
            return 7.5625 * t * t + 0.75
        elif t < 2.5 / 2.75:
            t -= 2.25 / 2.75
            return 7.5625 * t * t + 0.9375
        else:
            t -= 2.625 / 2.75
            return 7.5625 * t * t + 0.984375

    @staticmethod
    def _elastic_easing(t: float) -> float:
        """Elastic easing curve."""
        if t == 0 or t == 1:
            return t
        return -math.pow(2, 10 * (t - 1)) * math.sin((t - 1.075) * (2 * math.pi) / 0.3)


# ============================================================
# Envelope Point
# ============================================================

class Point(Generic[T]):
    """
    A single point in an envelope.
    Attributes:
        time  — non-negative timestamp
        value — value at this time
        ip    — interpolation type for the segment starting here
    """
    __slots__ = ('time', 'value', 'ip')

    def __init__(self, time: float, value: T, ip: IP = IP.FIXED):
        if time < 0:
            raise ValueError(f"Time cannot be negative: {time}")
        self.time = time
        self.value = value
        self.ip = ip

    def reverse_time(self, duration: float) -> None:
        """Mirror this point's time around the envelope duration."""
        self.time = duration - self.time

    def copy(self) -> 'Point[T]':
        """Return a shallow copy of this point."""
        return Point(self.time, self.value, self.ip)

    def to_dict(self) -> dict:
        """Serialize this point to a JSON-compatible dict."""
        return {'time': self.time, 'value': self.value, 'ip': self.ip.value}

    @classmethod
    def from_dict(cls, data: dict) -> 'Point':
        """Deserialize a point from a dict."""
        return cls(data['time'], data['value'], IP(data['ip']))

    def __repr__(self) -> str:
        return f"Point(t={self.time:.3f}, value={self.value}, ip={self.ip.value})"


# ============================================================
# Helper: numeric interpolation
# ============================================================

def _interpolate(a: T, b: T, t: float) -> T:
    """
    Linear interpolation between numeric values.

    Non-numeric values cannot be interpolated and return `a`.
    """
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return (1 - t) * a + t * b
    return a


# ============================================================
# Envelope
# ============================================================

class Envelope(Generic[T]):
    """
    A time-ordered collection of Points defining a value over time.

    Key properties:
    - Times must be strictly monotonic (non-decreasing).
    - The first added value defines the envelope's type.
    - get(t) clamps before the first point and holds after the last.
    - Interpolation is determined by the IP of the *next* point.
    """

    __slots__ = ('_points', '_value_type')

    def __init__(self):
        self._points: List[Point[T]] = []
        self._value_type = None  # Enforced after first insertion

    # ------------------------------------------------------------
    # Basic properties
    # ------------------------------------------------------------

    @property
    def points(self) -> List[Point[T]]:
        """Return a copy of the internal point list."""
        return self._points.copy()

    @property
    def is_empty(self) -> bool:
        """True if the envelope contains no points."""
        return len(self._points) == 0

    @property
    def duration(self) -> float:
        """Time of the last point, or 0.0 if empty."""
        return self._points[-1].time if self._points else 0.0

    # ------------------------------------------------------------
    # Adding points
    # ------------------------------------------------------------

    def append(self, time: float, value: T, ip: IP = IP.FIXED) -> 'Envelope[T]':
        """
        Add a point to the envelope.

        - Times must be non-negative and non-decreasing.
        - Duplicate times replace the existing point.
        - The first value defines the envelope's type.
        """
        if time < 0:
            raise ValueError(f"Time cannot be negative: {time}")

        # Type locking
        if self._value_type is None:
            self._value_type = type(value)
        elif not isinstance(value, self._value_type):
            raise TypeError(f"Expected {self._value_type.__name__}, got {type(value).__name__}")

        # Time ordering
        if self._points and time < self._points[-1].time:
            raise ValueError(f"Time {time} is before last point's time {self._points[-1].time}")

        point = Point(time, value, ip)

        # Replace if same time
        if self._points and time == self._points[-1].time:
            self._points[-1] = point
        else:
            self._points.append(point)

        return self

    def append_point(self, point: Point[T]) -> 'Envelope[T]':
        """Convenience wrapper for adding an existing Point."""
        return self.append(point.time, point.value, point.ip)

    # ------------------------------------------------------------
    # Value sampling
    # ------------------------------------------------------------

    def get(self, time: float) -> Optional[T]:
        """
        Sample the envelope at the given time.

        - Before first point: clamp to first value.
        - After last point: hold last value.
        - Between points: interpolate according to next point's IP.
        """
        if not self._points:
            return None

        # Clamp after last point
        if time >= self._points[-1].time:
            return self._points[-1].value

        # Clamp before first point
        if time <= self._points[0].time:
            return self._points[0].value

        # Find segment via bisect
        idx = bisect.bisect_right([p.time for p in self._points], time) - 1
        prev, nxt = self._points[idx], self._points[idx + 1]

        # Constant segments
        if nxt.ip in (IP.FIXED, IP.STEP):
            return prev.value

        # Interpolated segment
        t = (time - prev.time) / (nxt.time - prev.time)
        eased_t = IP.easing_function(nxt.ip)(t)
        return _interpolate(prev.value, nxt.value, eased_t)

    # ------------------------------------------------------------
    # Reversal
    # ------------------------------------------------------------

    def reverse(self) -> 'Envelope[T]':
        """
        Return a new envelope with time reversed.

        - Times are mirrored around the envelope duration.
        - Values are mirrored.
        - Interpolation types are reversed appropriately.
        """
        result = self.__class__()
        if not self._points:
            return result

        duration = self.duration
        orig_ips = [p.ip for p in self._points]
        n = len(self._points)

        # Mirror times and reverse order
        points = []
        for p in reversed(self._points):
            new_p = p.copy()
            new_p.time = duration - p.time
            points.append(new_p)

        # Assign reversed IPs
        for i in range(n - 1):
            points[i].ip = orig_ips[n - 1 - i].reversed
        points[-1].ip = IP.FIXED  # Last point has no outgoing segment

        result._points = points
        result._value_type = self._value_type
        return result

    # ------------------------------------------------------------
    # Serialization
    # ------------------------------------------------------------

    def to_dict(self) -> List[dict]:
        """Serialize the envelope to a list of dicts."""
        return [p.to_dict() for p in self._points]

    @classmethod
    def from_dict(cls, data: List[dict]) -> 'Envelope':
        """Deserialize an envelope from a list of dicts."""
        env = cls()
        for item in data:
            env.append_point(Point.from_dict(item))
        return env

    def to_json(self) -> str:
        """Serialize the envelope to a JSON string."""
        return json.dumps(self.to_dict(), indent=2)

    @classmethod
    def from_json(cls, json_str: str) -> 'Envelope':
        """Deserialize an envelope from a JSON string."""
        return cls.from_dict(json.loads(json_str))

    # ------------------------------------------------------------
    # Python protocol helpers
    # ------------------------------------------------------------

    def __len__(self) -> int:
        return len(self._points)

    def __getitem__(self, idx: int) -> Point[T]:
        return self._points[idx]

    def __iter__(self):
        return iter(self._points)

    def __str__(self) -> str:
        if not self._points:
            return "Empty Envelope"
        return "Envelope:\n  " + "\n  ".join(str(p) for p in self._points)
