from dataclasses import dataclass, field
from typing import Optional, Union, Any
from enum import Enum

# ============================================================================
# RANGE CONSTANTS (internal use only - for validation & clipping)
# ============================================================================

ARTICULATION_RANGE = (0.0, 2.0)  # articulation: 0.0 to 2.0
VOLUME_RANGE = (0, 100)  # volume: 0 to 100
PANNING_RANGE = (-1.0, 1.0)  # panning: -1.0 to 1.0
UNIT_RANGE = (0.0, 1.0)  # unit: 0.0 to 1.0 (density, scale)
OCTAVE_RANGE = (0, 8)  # octave: 0 to 8
MIDI_RANGE = (0, 127)  # midi: 0 to 127
PERCENTAGE_RANGE = (0, 100)  # percentage: 0 to 100 (times)

ConstantRef = str  # string reference to a named constant


# ============================================================================
# ENVELOPE SIMULATION
# ============================================================================

class Envelope:
    """Simulates a time-varying value"""

    def __init__(self, name: str = ""):
        self.name = name
        self._events = []  # (time, value) pairs

    def add_event(self, time: float, value: Any):
        self._events.append((time, value))
        self._events.sort()

    def value_at(self, time: float) -> Any:
        if not self._events:
            return None
        last_value = None
        for t, v in self._events:
            if t <= time:
                last_value = v
            else:
                break
        return last_value

    def __repr__(self):
        return f"Envelope({self.name})"


# ============================================================================
# HELPER FUNCTIONS
# ============================================================================

def clip_to_range(value: Any, range_tuple: tuple) -> Any:
    """Clip a single value to the given range"""
    if value is None or not isinstance(value, (int, float)):
        return value

    min_val, max_val = range_tuple
    return max(min_val, min(max_val, value))


# ============================================================================
# CONTEXT CLASS - Simple & Clean
# ============================================================================

@dataclass
class Context:
    """Music generation context with parent chaining"""

    # Parent for inheritance
    parent: Optional['Context'] = None

    # Core music parameters - users set single values!
    algo: Optional[Union[str, ConstantRef, Envelope]] = None  # algorithm
    a: Optional[Union[float, ConstantRef, Envelope]] = None  # articulation (0-2)
    c: Optional[Union[tuple, ConstantRef, Envelope]] = None  # color (r,g,b)
    d: Optional[Union[float, ConstantRef, Envelope]] = None  # density (0-1)
    k: Optional[Union[tuple, ConstantRef, Envelope]] = None  # key + accidental
    o: Optional[Union[int, Envelope]] = None  # octave (0-8)
    p: Optional[Union[float, ConstantRef, Envelope]] = None  # panning (-1 to 1)
    r: Optional[Union[int, Envelope]] = None  # repeat count
    s: Optional[Union[float, ConstantRef, Envelope]] = None  # scale (0-1)
    t: Optional[Union[int, Envelope]] = None  # tempo (bpm)
    times: Optional[Union[int, Envelope]] = None  # number of times (0-100)
    v: Optional[Union[int, float, ConstantRef, Envelope]] = None  # volume (0-100)
    midi: Optional[Union[int, ConstantRef, Envelope]] = None  # midi note (0-127)

    # Debug
    debug: bool = False
    indent: int = 0
    _current_time: float = 0.0

    # ========================================================================
    # PROPERTIES (long names for convenience)
    # ========================================================================

    @property
    def algorithm(self):
        return self.algo

    @property
    def articulation(self):
        return self.a

    @property
    def color(self):
        return self.c

    @property
    def density(self):
        return self.d

    @property
    def key(self):
        return self.k

    @property
    def octave(self):
        return self.o

    @property
    def panning(self):
        return self.p

    @property
    def repeat(self):
        return self.r

    @property
    def scale(self):
        return self.s

    @property
    def tempo(self):
        return self.t

    @property
    def volume(self):
        return self.v

    # ========================================================================
    # AUTO-CLIPPING when setting values
    # ========================================================================

    def __setattr__(self, name: str, value: Any):
        """Automatically clip values when set"""
        if not name.startswith('_') and value is not None:
            # Clip based on field name
            if name in ('a', 'articulation'):
                value = clip_to_range(value, ARTICULATION_RANGE)
            elif name in ('v', 'volume'):
                value = clip_to_range(value, VOLUME_RANGE)
            elif name in ('p', 'panning'):
                value = clip_to_range(value, PANNING_RANGE)
            elif name in ('d', 'density', 's', 'scale'):
                value = clip_to_range(value, UNIT_RANGE)
            elif name in ('o', 'octave'):
                value = clip_to_range(value, OCTAVE_RANGE)
            elif name == 'times':
                value = clip_to_range(value, PERCENTAGE_RANGE)
            elif name == 'midi':
                value = clip_to_range(value, MIDI_RANGE)
            # c (color) and k (key) are tuples - skip clipping

        super().__setattr__(name, value)

    def __post_init__(self):
        """Clip all fields after initialization"""
        for field in ['a', 'v', 'p', 'd', 's', 'o', 'times', 'midi']:
            if hasattr(self, field):
                setattr(self, field, getattr(self, field))

    # ========================================================================
    # TIME MANAGEMENT
    # ========================================================================

    def set_time(self, time: float):
        self._current_time = time

    def get_time(self) -> float:
        return self._current_time

    # ========================================================================
    # VALUE RESOLUTION (handles envelopes)
    # ========================================================================

    def _resolve(self, value: Any, time: Optional[float] = None) -> Any:
        """If envelope, get value at time; otherwise return as-is"""
        if isinstance(value, Envelope):
            t = time if time is not None else self._current_time
            return value.value_at(t)
        return value

    # ========================================================================
    # PARENT CHAIN LOOKUP
    # ========================================================================

    def get(self, name: str, time: Optional[float] = None) -> Any:
        """Get value, checking parent chain if needed"""

        # Handle times special case
        if name in ('times', '#'):
            if self.times is not None:
                return self._resolve(self.times, time)
            return self.parent.get(name, time) if self.parent else None

        # Map long names to short
        long_map = {
            'algorithm': 'algo', 'articulation': 'a', 'color': 'c',
            'density': 'd', 'key': 'k', 'octave': 'o', 'panning': 'p',
            'repeat': 'r', 'scale': 's', 'tempo': 't', 'volume': 'v'
        }
        attr = long_map.get(name, name)

        # Check locally
        if hasattr(self, attr):
            local = getattr(self, attr)
            if local is not None:
                return self._resolve(local, time)

        # Ask parent
        return self.parent.get(name, time) if self.parent else None

    def get_raw(self, name: str) -> Any:
        """Get raw value (could be envelope) without resolution"""
        if name in ('times', '#'):
            return self.times

        long_map = {
            'algorithm': 'algo', 'articulation': 'a', 'color': 'c',
            'density': 'd', 'key': 'k', 'octave': 'o', 'panning': 'p',
            'repeat': 'r', 'scale': 's', 'tempo': 't', 'volume': 'v'
        }
        attr = long_map.get(name, name)

        if hasattr(self, attr):
            local = getattr(self, attr)
            if local is not None:
                return local

        return self.parent.get_raw(name) if self.parent else None

    def set(self, name: str, value: Any):
        """Set value locally (with clipping)"""
        if name in ('times', '#'):
            self.times = value
            return

        long_map = {
            'algorithm': 'algo', 'articulation': 'a', 'color': 'c',
            'density': 'd', 'key': 'k', 'octave': 'o', 'panning': 'p',
            'repeat': 'r', 'scale': 's', 'tempo': 't', 'volume': 'v'
        }
        attr = long_map.get(name, name)

        if hasattr(self, attr):
            setattr(self, attr, value)

    def __getitem__(self, name: str) -> Any:
        return self.get(name)

    def __contains__(self, name: str) -> bool:
        return self.get_raw(name) is not None

    # ========================================================================
    # STRING REPRESENTATION
    # ========================================================================

    def __str__(self) -> str:
        parts = []
        for f in ['algo', 'a', 'c', 'd', 'k', 'o', 'p', 'r', 's', 't', 'times', 'v', 'midi']:
            val = getattr(self, f, None)
            if val is not None:
                if isinstance(val, Envelope):
                    parts.append(f"{f}=Env")
                elif isinstance(val, str) and len(val) > 10:
                    parts.append(f"{f}='{val[:8]}...'")
                else:
                    parts.append(f"{f}={val}")

        if self.debug:
            parts.append("debug")

        parent_info = f" ←{id(self.parent) % 1000}" if self.parent else ""
        time_info = f" @{self._current_time:.1f}s" if self._current_time != 0 else ""

        return f"Context({', '.join(parts)}{parent_info}{time_info})" if parts else f"Context({parent_info}{time_info})"


# ============================================================================
# DEMONSTRATION
# ============================================================================

def demo():
    print("=" * 70)
    print("SIMPLE CONTEXT - USER INPUTS SINGLE VALUES")
    print("=" * 70)

    # ===== User inputs single values (ranges are internal!) =====
    print("\n📋 USER INPUTS SINGLE VALUES:")

    root = Context(
        a=1.5,  # articulation
        v=80,  # volume
        p=-0.3,  # panning
        t=120,  # tempo
        debug=True
    )
    print(f"   root: {root}")

    # ===== Clipping happens automatically =====
    print("\n📋 AUTO-CLIPPING:")

    ctx = Context()
    ctx.a = 2.5  # too high - clips to 2.0
    ctx.v = 150  # too high - clips to 100
    ctx.p = -2.0  # too low - clips to -1.0

    print(f"   a=2.5 → {ctx.a} (clipped to {ARTICULATION_RANGE})")
    print(f"   v=150 → {ctx.v} (clipped to {VOLUME_RANGE})")
    print(f"   p=-2.0 → {ctx.p} (clipped to {PANNING_RANGE})")

    # ===== Envelopes still work =====
    print("\n📋 ENVELOPES:")

    swell = Envelope("swell")
    swell.add_event(0.0, 50)
    swell.add_event(2.0, 100)
    swell.add_event(4.0, 30)

    env_ctx = Context(v=swell)

    for t in [0.0, 1.0, 2.0, 3.0, 4.0, 5.0]:
        env_ctx.set_time(t)
        print(f"   t={t:.1f}s: volume={env_ctx.get('volume')}")

    # ===== Parent chain =====
    print("\n📋 PARENT CHAIN:")

    child = Context(parent=root, a=0.5, v=None)
    grand = Context(parent=child, p=0.8)

    print(f"   root: {root}")
    print(f"   child: {child}")
    print(f"   grand: {grand}")

    print(f"\n   grand.get('a'): {grand.get('a')} (from child)")
    print(f"   grand.get('v'): {grand.get('v')} (from root - child has None)")
    print(f"   grand.get('p'): {grand.get('p')} (local)")
    print(f"   grand.get('t'): {grand.get('t')} (from root)")


if __name__ == "__main__":
    demo()