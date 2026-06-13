# src/common/data/tempo.py

from fractions import Fraction
from enum import Enum
from dataclasses import dataclass
from typing import Optional, Tuple, List, Union

from common.elements.tempo import Tempo


# ═══════════════════════════════════════════════════════════════
# Tempo Markings (Factory Pattern)
# ═══════════════════════════════════════════════════════════════

class TempoMarking(Enum):
    """Tempo markings that can create Tempo instances with custom duration."""

    # Define tempo markings with their standard BPM
    larghissimo = 24
    adagissimo = 24
    grave = 35
    largo = 50
    lent = 52
    lento = 52
    larghetto = 63
    adagio = 71
    adagietto = 76
    marcia_moderato = 84
    andante = 92
    andantino = 94
    andante_moderato = 102
    moderato = 114
    allegretto = 116
    allegro_moderato = 118
    allegro = 138
    vivace = 166
    vivacissimo = 174
    allegrissimo = 174
    allegro_vivace = 174
    presto = 184
    prestissimo = 200

    def __call__(self, duration: Union[Fraction, int] = Fraction(1, 4)) -> Tempo:
        """Create a Tempo instance with the given duration."""
        return Tempo(duration, self.value)

    @property
    def bpm(self) -> int:
        """Get standard BPM for this tempo marking."""
        return self.value

    @classmethod
    def get(cls, name: str) -> Optional['TempoMarking']:
        """Get tempo marking by name."""
        try:
            return cls[name.lower()]
        except KeyError:
            return None


# ═══════════════════════════════════════════════════════════════
# Tempo Range Value
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class TempoRangeValue:
    """Tempo range with min and max values."""
    min_bpm: int
    max_bpm: int

    def __post_init__(self):
        """Ensure min <= max."""
        if self.min_bpm > self.max_bpm:
            object.__setattr__(self, 'min_bpm', self.max_bpm)
            object.__setattr__(self, 'max_bpm', self.min_bpm)

    @property
    def center(self) -> int:
        return (self.min_bpm + self.max_bpm) // 2

    @property
    def spread(self) -> int:
        return self.max_bpm - self.min_bpm

    def contains(self, bpm: int) -> bool:
        return self.min_bpm <= bpm <= self.max_bpm

    def clamp(self, bpm: int) -> int:
        return max(self.min_bpm, min(self.max_bpm, bpm))

    def normalize(self, bpm: int) -> float:
        if self.min_bpm == self.max_bpm:
            return 0.0
        return (bpm - self.min_bpm) / (self.max_bpm - self.min_bpm)


# ═══════════════════════════════════════════════════════════════
# Tempo Category
# ═══════════════════════════════════════════════════════════════

class TempoCategory(Enum):
    """Tempo categories with typical BPM ranges."""
    largo = TempoRangeValue(40, 60)
    lento = TempoRangeValue(45, 60)
    adagio = TempoRangeValue(60, 70)
    andante = TempoRangeValue(70, 85)
    moderato = TempoRangeValue(85, 100)
    allegro = TempoRangeValue(100, 130)
    vivace = TempoRangeValue(130, 160)
    presto = TempoRangeValue(160, 200)
    prestissimo = TempoRangeValue(200, 250)

    @property
    def min_bpm(self) -> int:
        return self.value.min_bpm

    @property
    def max_bpm(self) -> int:
        return self.value.max_bpm

    @property
    def center(self) -> int:
        return self.value.center

    @property
    def display_name(self) -> str:
        return self.name.capitalize()

    def contains(self, bpm: int) -> bool:
        return self.value.contains(bpm)

    @classmethod
    def from_bpm(cls, bpm: int) -> Optional['TempoCategory']:
        for category in cls:
            if category.contains(bpm):
                return category
        return None


# ═══════════════════════════════════════════════════════════════
# Global Tempo Configuration
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class TempoConfig:
    """Global tempo configuration."""
    min_bpm: int
    default_bpm: int
    max_bpm: int

    def __init__(self, min_bpm: int = 20, default_bpm: int = 120, max_bpm: int = 300):
        if not (min_bpm <= default_bpm <= max_bpm):
            raise ValueError(f"Default BPM {default_bpm} must be between min {min_bpm} and max {max_bpm}")

        object.__setattr__(self, 'min_bpm', min_bpm)
        object.__setattr__(self, 'default_bpm', default_bpm)
        object.__setattr__(self, 'max_bpm', max_bpm)

    def clamp(self, bpm: int) -> int:
        return max(self.min_bpm, min(self.max_bpm, bpm))

    def get_category(self, bpm: int) -> Optional[TempoCategory]:
        return TempoCategory.from_bpm(self.clamp(bpm))


# Singleton instances
TEMPO_CONFIG = TempoConfig()

# ═══════════════════════════════════════════════════════════════
# Example Usage - All Components Working Together
# ═══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("═" * 60)
    print("Complete Tempo Module - All Components")
    print("═" * 60)

    # 1. Create Tempo instances using TempoMarking factory
    print("\n🎵 1. Creating Tempo instances:")
    andante_quarter = TempoMarking.andante(4)  # Quarter note at andante
    allegro_eighth = TempoMarking.allegro(8)  # Eighth note at allegro
    print(f"   Andante quarter: {andante_quarter}")
    print(f"   Allegro eighth: {allegro_eighth}")

    # 2. Use TempoCategory to validate BPM ranges
    print("\n📊 2. Tempo Category validation:")
    for tempo in [andante_quarter, allegro_eighth]:
        category = TempoCategory.from_bpm(tempo.bpm)
        if category:
            print(f"   {tempo.bpm} BPM is in {category.display_name} range ({category.min_bpm}-{category.max_bpm})")

    # 3. Use TempoConfig to clamp out-of-range tempos
    print("\n⚙️ 3. TempoConfig clamping:")
    extreme = TempoMarking.prestissimo(4)  # 200 BPM
    print(f"   Prestissimo: {extreme.bpm} BPM")
    clamped = TEMPO_CONFIG.clamp(extreme.bpm)
    print(f"   Clamped to: {clamped} BPM (within {TEMPO_CONFIG.min_bpm}-{TEMPO_CONFIG.max_bpm})")

    # 4. Combine all: Create tempo, check category, ensure valid range
    print("\n🎯 4. Complete workflow:")


    def create_safe_tempo(marking: str, duration: int) -> Optional[Tempo]:
        tempo_marking = TempoMarking.get(marking)
        if not tempo_marking:
            return None

        tempo = tempo_marking(duration)
        if not TEMPO_CONFIG.min_bpm <= tempo.bpm <= TEMPO_CONFIG.max_bpm:
            print(f"   Warning: {tempo.bpm} BPM out of range, clamping")
            clamped_bpm = TEMPO_CONFIG.clamp(tempo.bpm)
            tempo = Tempo(duration, clamped_bpm)

        category = TempoCategory.from_bpm(tempo.bpm)
        if category:
            print(f"   Created: {tempo} ({category.display_name})")
        return tempo


    create_safe_tempo("andante", 4)
    create_safe_tempo("prestissimo", 4)
    create_safe_tempo("largo", 2)

    # 5. Tempo operations with category awareness
    print("\n🔢 5. Tempo operations:")
    original = TempoMarking.moderato(4)
    print(f"   Original: {original}")

    faster = original * 1.5
    category = TempoCategory.from_bpm(faster.bpm)
    print(f"   ×1.5: {faster} ({category.display_name if category else 'Unknown'})")

    slower = original / 2
    category = TempoCategory.from_bpm(slower.bpm)
    print(f"   /2: {slower} ({category.display_name if category else 'Unknown'})")

    # 6. Duration calculations
    print("\n⏱️ 6. Duration calculations:")
    tempo = TempoMarking.adagio(4)  # Quarter note at adagio
    print(f"   {tempo}")
    print(f"   Quarter note duration: {tempo.duration_in_ms(Fraction(1, 4))} ms")
    print(f"   Eighth note duration: {tempo.duration_in_ms(Fraction(1, 8))} ms")

    print("\n✅ Done!")