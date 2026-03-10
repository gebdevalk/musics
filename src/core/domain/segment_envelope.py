# segment_envelope.py

import matplotlib.pyplot as plt
from dataclasses import dataclass
from typing import Any, Callable
import bisect
import math
from enum import Enum

# ---------------------------
# Interpolation Enum (IP)
# ---------------------------
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

    @staticmethod
    def easing_function(ip_type: 'IP'):
        """Return the easing function for a given interpolation type."""
        return {
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
        }.get(ip_type, lambda t: t)

    @staticmethod
    def _bounce_easing(t: float) -> float:
        if t < 1/2.75: return 7.5625 * t * t
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

# ---------------------------
# Segment and Envelope
# ---------------------------
@dataclass(slots=True)
class Segment:
    t0: float # start time
    dur: float # duration
    v0: Any # start value
    v1: Any # start value
    ip: IP # store IP enum
    lerp: Callable[[Any, Any, float], Any]
    getter: Callable[[Any], Any] = lambda v: v

@property
    def end_time(self):
        return self.t0 + self.dur

def segment_to_lambda(seg: Segment):
    """Compile a segment into a callable lambda using the IP enum."""
    t0 = seg.t0
    dur = seg.dur
    v0 = seg.v0
    v1 = seg.v1
    func = IP.easing_function(seg.ip)

    if dur <= 0 or v0 == v1 or seg.ip == IP.FIXED:
        return lambda t: v1

    inv_dur = 1.0 / dur

    def f(t):
        u = (t - t0) * inv_dur
        u = max(0.0, min(1.0, u)) # clamp to [0,1]
        return v0 + (v1 - v0) * func(u)

    return f

def compile_envelope(segments):
    """Compile multiple segments into a single callable envelope."""
    segments = sorted(segments, key=lambda s: s.t0)
    if not segments:
        return lambda t: 0.0

    bounds = [s.end_time for s in segments]
    funcs = [segment_to_lambda(s) for s in segments]

    t_start = segments[0].t0
    t_end = segments[-1].end_time
    first_val = segments[0].v0
    last_val = segments[-1].v1

    def envelope_fn(t):
        if t <= t_start:
            return first_val
        if t >= t_end:
            return last_val
        idx = bisect.bisect_right(bounds, t)
        return funcs[idx](t)

    return envelope_fn

class Envelope:
    """Thin wrapper for convenience."""
    def __init__(self, segments):
        self.segments = list(segments)
        self._fn = compile_envelope(self.segments)

    def __call__(self, t):
        return self._fn(t)

# ---------------------------
# Plotting helper
# ---------------------------
def plot_envelope(env, t_start, t_end, steps=500, label="Envelope"):
    ts = [t_start + (t_end - t_start) * i / (steps-1) for i in range(steps)]
    vs = [env(t) for t in ts]

    # Handle tuples
    if isinstance(vs[0], tuple):
        for i in range(len(vs[0])):
            plt.plot(ts, [v[i] for v in vs], label=f"{label}[{i}]")
    else:
        plt.plot(ts, vs, label=label)

    plt.xlabel("Time")
    plt.ylabel("Value")
    plt.title("Envelope Plot")
    plt.grid(True)
    plt.legend()

# ---------------------------
# Demo examples with explanatory comments
# ---------------------------
def main():
    # Example 1: Multiple curves on numeric values
    segments = [
        Segment(0.0, 1.0, 0.0, 0.0, IP.FIXED), # Flat / hold
        Segment(1.0, 1.0, 0.0, 1.0, IP.LINEAR), # Linear ramp
        Segment(2.0, 1.0, 1.0, 2.0, IP.EASE_IN), # Quadratic ease-in
        Segment(3.0, 1.0, 2.0, 1.0, IP.EASE_OUT), # Quadratic ease-out
        Segment(4.0, 1.0, 1.0, 2.0, IP.BOUNCE), # Bounce
        Segment(5.0, 1.0, 2.0, 3.0, IP.ELASTIC), # Elastic
    ]
    env = Envelope(segments)
    plot_envelope(env, t_start=0, t_end=6, label="Numeric Curves Example")
    plt.show()

    # Example 2: Tuple envelope for multi-parameter control
    segments_tuple = [
        Segment(0.0, 1.0, (0,0), (0,0), IP.FIXED),
        Segment(1.0, 2.0, (0,0), (1,5), IP.SMOOTH), # Smooth interpolation of tuple
        Segment(3.0, 1.0, (1,5), (2,10), IP.SINE),
    ]
    env_tuple = Envelope(segments_tuple)
    plot_envelope(env_tuple, t_start=0, t_end=4, label="Tuple Curves Example")
    plt.show()

if __name__ == "__main__":
    main()
