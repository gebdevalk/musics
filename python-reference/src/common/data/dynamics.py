# src/common/data/dynamics.py
"""
Volume and dynamics reference data (normalised 0–100 scale, CC7, CC11).

Pure dict literals. No classes, no mutation.
"""

from dataclasses import dataclass
from typing import Optional, Dict, Any
from enum import Enum


# ═══════════════════════════════════════════════════════════════
# Dynamic Markings (MIDI velocity values)
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class DynamicValue:
    """Dynamic marking with MIDI velocity."""
    velocity: int
    name: str

    def __int__(self) -> int:
        return self.velocity


class Dynamic(Enum):
    """Standard dynamic markings with MIDI velocities."""
    silence = DynamicValue(0, "silence")
    pppp = DynamicValue(10, "pppp")
    ppp = DynamicValue(20, "ppp")
    pp = DynamicValue(30, "pp")
    p = DynamicValue(40, "p")
    mp = DynamicValue(50, "mp")
    mf = DynamicValue(60, "mf")
    f = DynamicValue(70, "f")
    ff = DynamicValue(80, "ff")
    fff = DynamicValue(90, "fff")
    ffff = DynamicValue(100, "ffff")

    @property
    def velocity(self) -> int:
        return self.value.velocity

    @classmethod
    def get(cls, key: str) -> Optional['Dynamic']:
        """Get dynamic by name."""
        try:
            return cls[key.lower()]
        except KeyError:
            return None

    @classmethod
    def from_velocity(cls, velocity: int) -> Optional['Dynamic']:
        """Get closest dynamic marking from velocity value."""
        closest = min(cls, key=lambda d: abs(d.velocity - velocity))
        return closest if closest.velocity != velocity else None


# ═══════════════════════════════════════════════════════════════
# Dynamic Ranges by Instrument Family
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class DynamicRange:
    """Dynamic range for an instrument family."""
    min: int
    max: int
    typical: int

    def clamp(self, velocity: int) -> int:
        """Clamp velocity to valid range."""
        return max(self.min, min(self.max, velocity))

    def normalize(self, velocity: int) -> float:
        """Normalize velocity to 0.0-1.0 within range."""
        if self.min == self.max:
            return 0.0
        return (velocity - self.min) / (self.max - self.min)

    def denormalize(self, normalized: float) -> int:
        """Convert normalized 0.0-1.0 to velocity within range."""
        return self.min + int(normalized * (self.max - self.min))


class InstrumentFamily(Enum):
    """Instrument families with their dynamic ranges."""
    piano = DynamicRange(30, 100, 70)
    strings = DynamicRange(20, 110, 75)
    woodwinds = DynamicRange(35, 105, 70)
    brass = DynamicRange(40, 127, 90)
    percussion = DynamicRange(60, 127, 100)
    voice = DynamicRange(30, 100, 75)
    synth = DynamicRange(0, 127, 80)

    @property
    def range(self) -> DynamicRange:
        return self.value

    @classmethod
    def get(cls, name: str) -> Optional['InstrumentFamily']:
        """Get instrument family by name."""
        try:
            return cls[name.lower()]
        except KeyError:
            return None


# ═══════════════════════════════════════════════════════════════
# MIDI CC7 Volume Range
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class CC7VolumeRange:
    """MIDI CC7 volume range with presets."""
    min: int = 0
    max: int = 127
    default: int = 100

    # Preset values
    off: int = 0
    very_soft: int = 20
    soft: int = 40
    medium: int = 70
    loud: int = 100
    very_loud: int = 120

    def clamp(self, value: int) -> int:
        """Clamp value to valid range."""
        return max(self.min, min(self.max, value))

    def normalize(self, value: int) -> float:
        """Normalize value to 0.0-1.0 within range."""
        return (value - self.min) / (self.max - self.min)


# Singleton instance
CC7_VOLUME = CC7VolumeRange()


# ═══════════════════════════════════════════════════════════════
# MIDI CC11 Expression Range
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class CC11ExpressionRange:
    """MIDI CC11 expression range with presets."""
    min: int = 0
    max: int = 127
    default: int = 127

    # Preset values
    soft: int = 40
    medium: int = 80
    loud: int = 120

    def clamp(self, value: int) -> int:
        """Clamp value to valid range."""
        return max(self.min, min(self.max, value))

    def normalize(self, value: int) -> float:
        """Normalize value to 0.0-1.0 within range."""
        return (value - self.min) / (self.max - self.min)


# Singleton instance
EXPRESSION = CC11ExpressionRange()


# ═══════════════════════════════════════════════════════════════
# Convenience lookup functions (backward compatible)
# ═══════════════════════════════════════════════════════════════

def get_dynamic(name: str) -> Optional[Dynamic]:
    """Get dynamic marking by name."""
    return Dynamic.get(name)


def get_dynamic_velocity(name: str) -> int:
    """Get velocity value for dynamic marking."""
    dyn = Dynamic.get(name)
    return dyn.velocity if dyn else 0


def get_instrument_range(instrument: str) -> Optional[DynamicRange]:
    """Get dynamic range for instrument family."""
    family = InstrumentFamily.get(instrument)
    return family.range if family else None

"""
# Access dynamics
forte_level = DYNAMICS['f']  # 70

# Instrument range
piano_max = INSTRUMENT_DYNAMIC_RANGES['piano']['max']  # 100

# Volume levels
medium_vol = CC7_VOLUME['medium']  # 70

# Access values
expression_default = EXPRESSION['default']  # 127
expression_soft = EXPRESSION['soft']        # 40
"""