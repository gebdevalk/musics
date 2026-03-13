# point_envelope.py

from enum import Enum
from typing import Generic, TypeVar, List, Optional, Tuple, Any, Callable
import json
import bisect
import matplotlib.pyplot as plt
import numpy as np
import math

T = TypeVar('T')


class IP(Enum):
    """Interpolation types for envelope points."""
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
        """Return the interpolation type when time is reversed."""
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
        if t == 0 or t == 1:
            return t
        return -math.pow(2, 10 * (t - 1)) * math.sin((t - 1.075) * (2 * math.pi) / 0.3)


class Point(Generic[T]):
    """A mutable point in an envelope."""

    def __init__(self, time: float, value: T, ip: IP = IP.FIXED):
        if time < 0:
            raise ValueError(f"Time cannot be negative: {time}")
        self.time = time
        self.value = value
        self.ip = ip

    def reverse_time(self, duration: float) -> None:
        self.time = duration - self.time

    def copy(self) -> 'Point[T]':
        return Point(self.time, self.value, self.ip)

    def to_dict(self) -> dict:
        return {'time': self.time, 'value': self.value, 'ip': self.ip.value}

    @classmethod
    def from_dict(cls, data: dict) -> 'Point':
        return cls(data['time'], data['value'], IP(data['ip']))

    def __repr__(self) -> str:
        return f"Point(t={self.time:.3f}, value={self.value}, ip={self.ip.value})"


class Envelope(Generic[T]):
    """A collection of points defining a value over time."""

    def __init__(self):
        self._points: List[Point[T]] = []
        self._value_type = None

    @property
    def points(self) -> List[Point[T]]:
        return self._points.copy()

    @property
    def is_empty(self) -> bool:
        return len(self._points) == 0

    @property
    def duration(self) -> float:
        return self._points[-1].time if self._points else 0.0

    def add(self, time: float, value: T, ip: IP = IP.FIXED) -> 'Envelope[T]':
        if time < 0:
            raise ValueError(f"Time cannot be negative: {time}")
        if self._value_type is None:
            self._value_type = type(value)
        elif not isinstance(value, self._value_type):
            raise TypeError(f"Expected {self._value_type.__name__}, got {type(value).__name__}")
        if self._points and time < self._points[-1].time:
            raise ValueError(f"Time {time} is before last point's time {self._points[-1].time}")

        point = Point(time, value, ip)
        if self._points and time == self._points[-1].time:
            self._points[-1] = point
        else:
            self._points.append(point)
        return self

    def add_point(self, point: Point[T]) -> 'Envelope[T]':
        return self.add(point.time, point.value, point.ip)

    def get(self, time: float) -> Optional[T]:
        if not self._points:
            return None
        if time >= self._points[-1].time:
            return self._points[-1].value
        if time <= self._points[0].time:
            return self._points[0].value

        idx = bisect.bisect_right([p.time for p in self._points], time) - 1
        prev, nxt = self._points[idx], self._points[idx + 1]

        if nxt.ip in (IP.FIXED, IP.STEP):
            return prev.value

        t = (time - prev.time) / (nxt.time - prev.time)
        eased_t = IP.easing_function(nxt.ip)(t)
        return self._interpolate(prev.value, nxt.value, eased_t)

    def _interpolate(self, a: T, b: T, t: float) -> T:
        if isinstance(a, (int, float)) and isinstance(b, (int, float)):
            return (1 - t) * a + t * b
        return a

    def reverse(self) -> 'Envelope[T]':
        """Create a reversed copy of this envelope."""
        result = self.__class__()
        if not self._points:
            return result

        duration = self.duration
        orig_ips = [p.ip for p in self._points]
        n = len(self._points)

        # Copy points in reverse order with flipped times
        points = []
        for p in reversed(self._points):
            new_p = p.copy()
            new_p.time = duration - p.time
            points.append(new_p)

        # New point i's IP describes the segment it starts, which in the original
        # was the segment ending at orig index (n-1-i), driven by orig_ips[n-1-i].
        # The last point has no outgoing segment → FIXED.
        for i in range(n - 1):
            points[i].ip = orig_ips[n - 1 - i].reversed
        points[-1].ip = IP.FIXED

        result._points = points
        result._value_type = self._value_type
        return result

    def _assert_reversible(self) -> None:
        if not self._points:
            return
        original = self.to_dict()
        twice = self.reverse().reverse().to_dict()
        assert original == twice, "Double reversal should return to original"
        print("✓ Double reversal test passed")

    def display(self, resolution: int = 1000, show_points: bool = True,
                title: str = "Envelope", figsize: Tuple[int, int] = (12, 6)) -> None:
        if not self._points:
            print("Empty envelope")
            return

        times = np.linspace(0, self.duration, resolution)
        values = [self.get(t) for t in times]

        if not all(isinstance(v, (int, float)) for v in values if v is not None):
            print("Cannot plot non-numeric values")
            return

        colors = {
            IP.FIXED: '#ff9999', IP.LINEAR_UP: '#99ff99', IP.LINEAR_DOWN: '#88ee88',
            IP.SMOOTH: '#9999ff', IP.EASE_IN: '#ffff99', IP.EASE_OUT: '#ffb3e6',
            IP.EASE_IN_OUT: '#c2c2f0', IP.STEP: '#ffcc99', IP.BOUNCE: '#99ffff',
            IP.ELASTIC: '#ff99cc', IP.SINE: '#c2f0c2', IP.EXPONENTIAL: '#f0c2c2',
            IP.LOGARITHMIC: '#c2c2f0', IP.SQUARE_ROOT: '#f0f0c2', IP.CUBIC_ROOT: '#c2f0f0',
        }

        plt.figure(figsize=figsize)
        plt.plot(times, values, 'b-', linewidth=2)

        if show_points:
            plt.plot([p.time for p in self._points],
                     [p.value for p in self._points],
                     'ro', markersize=8, zorder=5)

            for i, p in enumerate(self._points):
                if i > 0:
                    plt.annotate(p.ip.value, (p.time, p.value), xytext=(10, 5),
                                 textcoords='offset points',
                                 bbox=dict(boxstyle="round,pad=0.3",
                                           facecolor=colors.get(p.ip, '#cccccc'),
                                           alpha=0.7),
                                 fontsize=8)

        plt.xlabel('Time')
        plt.ylabel('Value')
        plt.title(title)
        plt.grid(True, alpha=0.3)
        plt.tight_layout()
        plt.show()

    def to_dict(self) -> List[dict]:
        return [p.to_dict() for p in self._points]

    @classmethod
    def from_dict(cls, data: List[dict]) -> 'Envelope':
        env = cls()
        for item in data:
            env.add_point(Point.from_dict(item))
        return env

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), indent=2)

    @classmethod
    def from_json(cls, json_str: str) -> 'Envelope':
        return cls.from_dict(json.loads(json_str))

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


if __name__ == "__main__":
    def print_envelope(label, env):
        print(f"\n{label}:")
        for p in env:
            print(f"  {p}")

    # --- Test 1: Basic reversal structure ---
    print("=== Test 1: Basic reversal ===")
    env = (Envelope()
           .add(0.0,  0.0,   IP.FIXED)
           .add(1.0,  10.0,  IP.LINEAR_UP)
           .add(2.0,  50.0,  IP.EASE_IN)
           .add(3.0,  100.0, IP.FIXED))

    print_envelope("Original", env)
    rev = env.reverse()
    print_envelope("Reversed", rev)
    rev2 = rev.reverse()
    print_envelope("Double reversed", rev2)

    assert env.to_dict() == rev2.to_dict(), "❌ Double reversal failed"
    print("✓ Double reversal matches original")

    # --- Test 2: IP swap correctness ---
    print("\n=== Test 2: IP swap correctness ===")
    expected_ips = [IP.FIXED, IP.EASE_OUT, IP.LINEAR_DOWN, IP.FIXED]
    actual_ips   = [p.ip for p in rev]
    assert actual_ips == expected_ips, f"❌ IP mismatch\n  expected {expected_ips}\n  got      {actual_ips}"
    print(f"✓ IPs correct: {[ip.value for ip in actual_ips]}")

    # --- Test 3: Values are mirrored ---
    print("\n=== Test 3: Value mirroring ===")
    orig_vals = [p.value for p in env]
    rev_vals  = [p.value for p in rev]
    assert orig_vals == list(reversed(rev_vals)), "❌ Values not mirrored"
    print(f"✓ Values mirrored correctly")

    # --- Test 4: Times are mirrored ---
    print("\n=== Test 4: Time mirroring ===")
    orig_times     = [p.time for p in env]
    rev_times      = [p.time for p in rev]
    expected_times = [env.duration - t for t in reversed(orig_times)]
    assert rev_times == expected_times, f"❌ Times not mirrored\n  expected {expected_times}\n  got {rev_times}"
    print(f"✓ Times mirrored correctly")

    # --- Test 5: Self-reversing IPs ---
    print("\n=== Test 5: Self-reversing IPs (FIXED, STEP, SMOOTH) ===")
    env2 = (Envelope()
            .add(0.0, 0.0, IP.FIXED)
            .add(1.0, 1.0, IP.SMOOTH)
            .add(2.0, 2.0, IP.STEP)
            .add(3.0, 3.0, IP.FIXED))

    assert env2.to_dict() == env2.reverse().reverse().to_dict(), "❌ Double reversal failed"
    print(f"✓ Self-reversing IPs: {[p.ip.value for p in env2.reverse()]}")

    # --- Test 6: Single point ---
    print("\n=== Test 6: Single point ===")
    env3 = Envelope().add(0.0, 42.0, IP.FIXED)
    rev3 = env3.reverse()
    assert rev3[0].value == 42.0 and rev3[0].time == 0.0, "❌ Single point reversal failed"
    print("✓ Single point reversal correct")

    # --- Test 7: Empty envelope ---
    print("\n=== Test 7: Empty envelope ===")
    assert Envelope().reverse().is_empty, "❌ Reversed empty should be empty"
    print("✓ Empty envelope reversal correct")

    print("\n✓ All tests passed")