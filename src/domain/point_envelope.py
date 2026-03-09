# point_envelope.py
# PointEnvelope

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
    LINEAR = "linear"
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
        """Return the easing function for a given interpolation type."""
        functions = {
            IP.FIXED: lambda t: 0.0,
            IP.LINEAR: lambda t: t,
            IP.SMOOTH: lambda t: t * t * (3 - 2 * t),
            IP.EASE_IN: lambda t: t * t,
            IP.EASE_OUT: lambda t: 1 - (1 - t) * (1 - t),
            IP.EASE_IN_OUT: lambda t: 2 * t * t if t < 0.5 else 1 - pow(-2 * t + 2, 2) / 2,
            IP.STEP: lambda t: 0.0 if t < 1.0 else 1.0,
            IP.BOUNCE: IP._bounce_easing,
            IP.ELASTIC: IP._elastic_easing,
            IP.SINE: lambda t: (1 - math.cos(t * math.pi)) / 2,
            IP.EXPONENTIAL: lambda t: math.pow(2, 10 * (t - 1)),
            IP.LOGARITHMIC: lambda t: math.log(t * 9 + 1, 10),
            IP.SQUARE_ROOT: lambda t: math.sqrt(t),
            IP.CUBIC_ROOT: lambda t: math.pow(t, 1/3),
        }
        return functions.get(ip_type, functions[IP.LINEAR])
    
    @staticmethod
    def _bounce_easing(t: float) -> float:
        if t < 1/2.75:
            return 7.5625 * t * t
        elif t < 2/2.75:
            t -= 1.5/2.75
            return 7.5625 * t * t + 0.75
        elif t < 2.5/2.75:
            t -= 2.25/2.75
            return 7.5625 * t * t + 0.9375
        else:
            t -= 2.625/2.75
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
        """Reverse this point's time in place."""
        self.time = duration - self.time
    
    def copy(self) -> 'Point[T]':
        """Create a copy of this point."""
        return Point(self.time, self.value, self.ip)
    
    def to_dict(self) -> dict:
        return {'time': self.time, 'value': self.value, 'ip': self.ip.value}
    
    @classmethod
    def from_dict(cls, data: dict) -> 'Point':
        return cls(data['time'], data['value'], IP(data['ip']))
    
    def __repr__(self) -> str:
        ip_str = f", ip={self.ip.value}" if self.ip != IP.FIXED else ""
        return f"Point(t={self.time:.3f}, value={self.value}{ip_str})"


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
        """Add a point to the envelope."""
        if time < 0:
            raise ValueError(f"Time cannot be negative: {time}")
        
        # Type checking
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
    
    def get(self, time: float) -> Optional[T]:
        """Get value at specified time."""
        if not self._points:
            return None
        
        if time >= self._points[-1].time:
            return self._points[-1].value
        if time <= self._points[0].time:
            return self._points[0].value
        
        # Binary search for surrounding points
        idx = bisect.bisect_right([p.time for p in self._points], time) - 1
        prev, nxt = self._points[idx], self._points[idx + 1]
        
        if nxt.ip in (IP.FIXED, IP.STEP):
            return prev.value
        
        t = (time - prev.time) / (nxt.time - prev.time)
        eased_t = IP.easing_function(nxt.ip)(t)
        
        return self._interpolate(prev.value, nxt.value, eased_t)
    
    def _interpolate(self, a: T, b: T, t: float) -> T:
        """Interpolate between two values."""
        if isinstance(a, (int, float)) and isinstance(b, (int, float)):
            return (1 - t) * a + t * b
        return a
    
    def reverse(self) -> 'Envelope[T]':
        """Create a reversed copy of this envelope."""
        return Envelope[T]()._reversed_from(self)

    # def _reversed_from(self, other: 'Envelope[T]') -> 'Envelope[T]':
    #     """Create this envelope as the reverse of another."""
    #     if not other._points:
    #         return self
    #
    #     # Copy and reverse points
    #     self._points = [p.copy() for p in reversed(other._points)]
    #     duration = other.duration
    #
    #     # Reverse times in place
    #     for p in self._points:
    #         p.reverse_time(duration)
    #
    #     # Transform interpolations following the FXXX -> XXXF pattern
    #     if len(self._points) > 1:
    #         # Store original interpolations
    #         orig_ips = [p.ip for p in self._points]
    #
    #         # Shift and reverse: each point gets the reversed IP of the previous original point
    #         # First point gets FIXED
    #         self._points[0].ip = IP.FIXED
    #
    #         # Remaining points get reversed IP of the previous original point
    #         for i in range(1, len(self._points)):
    #             self._points[i].ip = orig_ips[i - 1].reversed
    #
    #     self._value_type = other._value_type
    #     return self

    def _reversed_from(self, other: 'Envelope[T]') -> 'Envelope[T]':
        """Create this envelope as the reverse of another."""
        if not other._points:
            return self

        # Copy and reverse points
        self._points = [p.copy() for p in reversed(other._points)]
        duration = other.duration

        # Reverse times in place
        for p in self._points:
            p.reverse_time(duration)

        # Transform interpolations following the FXXX -> XXXF pattern
        if len(self._points) > 1:
            # Store original interpolations
            orig_ips = [p.ip for p in self._points]

            # First point becomes FIXED
            self._points[0].ip = IP.FIXED

            # Remaining points get reversed IP of the previous original point
            for i in range(1, len(self._points)):
                self._points[i].ip = orig_ips[i - 1].reversed

            # Fix adjacent FIXED/STEP and variable types
            for i in range(len(self._points) - 1):
                current_is_fixed_step = self._points[i].ip in (IP.FIXED, IP.STEP)
                next_is_fixed_step = self._points[i + 1].ip in (IP.FIXED, IP.STEP)

                # If one is fixed/step and the other is variable, swap them
                if current_is_fixed_step != next_is_fixed_step:
                    self._points[i], self._points[i + 1] = self._points[i + 1], self._points[i]

        self._value_type = other._value_type
        return self
    
    def _assert_reversible(self) -> None:
        """Test that double reversal returns to original."""
        if not self._points:
            return
        
        original = self.to_dict()
        twice = self.reverse().reverse().to_dict()
        
        assert original == twice, "Double reversal should return to original"
        print("✓ Double reversal test passed")
    
    def display(self, resolution: int = 1000, show_points: bool = True,
                title: str = "Envelope", figsize: Tuple[int, int] = (12, 6)) -> None:
        """Display the envelope."""
        if not self._points:
            print("Empty envelope")
            return
        
        # Sample values
        times = np.linspace(0, self.duration, resolution)
        values = [self.get(t) for t in times]
        
        if not all(isinstance(v, (int, float)) for v in values if v is not None):
            print("Cannot plot non-numeric values")
            return
        
        # Plot
        plt.figure(figsize=figsize)
        plt.plot(times, values, 'b-', linewidth=2)
        
        if show_points:
            # Keyframes
            plt.plot([p.time for p in self._points], 
                    [p.value for p in self._points], 
                    'ro', markersize=8, zorder=5)
            
            # IP labels with colors
            colors = {
                IP.FIXED: '#ff9999', IP.LINEAR: '#99ff99', IP.SMOOTH: '#9999ff',
                IP.EASE_IN: '#ffff99', IP.EASE_OUT: '#ffb3e6', IP.EASE_IN_OUT: '#c2c2f0',
                IP.STEP: '#ffcc99', IP.BOUNCE: '#99ffff', IP.ELASTIC: '#ff99cc',
                IP.SINE: '#c2f0c2', IP.EXPONENTIAL: '#f0c2c2', IP.LOGARITHMIC: '#c2c2f0',
                IP.SQUARE_ROOT: '#f0f0c2', IP.CUBIC_ROOT: '#c2f0f0',
            }
            
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
    
    def add_point(self, point: Point[T]) -> 'Envelope[T]':
        """Add an existing point."""
        return self.add(point.time, point.value, point.ip)
    
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


# Example
if __name__ == "__main__":
    # Create envelope
    env = Envelope[float]()\
        .add(0.0, 0.0, IP.LINEAR)\
        .add(1.0, 5.0, IP.SMOOTH)\
        .add(2.0, 2.0, IP.EASE_IN)\
        .add(3.0, 8.0, IP.EASE_OUT)\
        .add(4.0, 3.0, IP.BOUNCE)
    
    print("Original:")
    print(env)
    
    # Test double reversal
    env._assert_reversible()
    
    # Show reversed
    rev = env.reverse()
    print("\nReversed:")
    print(rev)