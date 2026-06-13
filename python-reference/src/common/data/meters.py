# src/common/data/meters.py
"""
Meter, timing, and audio reference data.
"""

from dataclasses import dataclass
from enum import Enum
from typing import Optional, Tuple, List, Union
from fractions import Fraction


# ═══════════════════════════════════════════════════════════════
# Time Signature Value
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class TimeSignatureValue:
    """Time signature with numerator, denominator, and optional subdivisions."""
    numerator: int
    denominator: int
    subdivisions: Optional[Tuple[int, ...]] = None

    def __post_init__(self):
        """Validate time signature."""
        if self.numerator <= 0:
            raise ValueError(f"Numerator must be positive, got {self.numerator}")
        if self.denominator not in (1, 2, 4, 8, 16, 32):
            raise ValueError(f"Denominator must be power of 2, got {self.denominator}")

        # Validate additive subdivisions
        if self.subdivisions:
            if sum(self.subdivisions) != self.numerator:
                raise ValueError(
                    f"Subdivisions {self.subdivisions} sum to {sum(self.subdivisions)}, "
                    f"but numerator is {self.numerator}"
                )
            # All subdivisions must be 2 or 3
            if any(s not in (2, 3) for s in self.subdivisions):
                raise ValueError(f"Subdivisions must be 2 or 3, got {self.subdivisions}")

    @property
    def beats_per_bar(self) -> int:
        """Number of beats per bar."""
        if self.is_compound:
            return self.numerator // 3
        return self.numerator

    @property
    def beat_unit(self) -> int:
        """Note value that gets one beat."""
        return self.denominator

    @property
    def as_fraction(self) -> Fraction:
        """Time signature as Fraction (numerator/denominator)."""
        return Fraction(self.numerator, self.denominator)

    @property
    def is_additive(self) -> bool:
        """Whether time signature has explicit subdivisions."""
        return self.subdivisions is not None

    # At line ~50-70, replace beat_grouping with:

    @property
    def beat_grouping(self) -> List[int]:
        """Get beat grouping in terms of 2's and 3's."""
        if self.is_additive:
            return list(self.subdivisions)

        # Calculate for simple/compound
        if self.is_compound:
            # Compound: each beat is a dotted note (3 subdivisions)
            return [3] * (self.numerator // 3)
        else:
            # Simple: group beats into 2's and 3's for display
            beats = self.numerator
            if beats == 2:
                return [2]
            elif beats == 3:
                return [3]
            elif beats == 4:
                return [2, 2]
            else:
                return [1] * beats  # fallback for other simple meters

    @property
    def subdivision_grouping(self) -> List[int]:
        """Get subdivision grouping in terms of 2's and 3's (more detailed)."""
        if self.is_additive:
            return list(self.subdivisions)
        elif self.is_compound:
            # Compound: each beat divides into 3
            return [3] * (self.numerator // 3)
        else:
            # Simple: each beat divides into 2 (if denominator is 8 or higher)
            if self.denominator >= 8:
                return [2] * self.numerator
            else:
                # For quarter note beats, each beat is 1 (no subdivision grouping)
                return [1] * self.numerator

    @property
    def beat_count_type(self) -> str:
        """Return 'duple', 'triple', 'quadruple', or 'complex'."""
        beats = self.beats_per_bar
        if beats == 2:
            return "duple"
        elif beats == 3:
            return "triple"
        elif beats == 4:
            return "quadruple"
        else:
            return "complex"

    @property
    def is_simple(self) -> bool:
        """Whether time signature is simple (each beat divides into 2)."""
        return not self.is_compound and not self.is_additive

    @property
    def is_compound(self) -> bool:
        """Whether time signature is compound (each beat divides into 3)."""
        return (self.denominator in (8, 16, 32) and
                self.numerator % 3 == 0 and
                self.numerator != 3 and
                not self.is_additive)

    @property
    def is_complex(self) -> bool:
        """Whether time signature is complex (additive/asymmetric)."""
        return self.is_additive or (self.beat_count_type == "complex" and not self.is_compound)

    @property
    def notation(self) -> str:
        """Get common notation name."""
        if self.numerator == 2 and self.denominator == 2:
            return "cut time"
        elif self.numerator == 4 and self.denominator == 4:
            return "common time"
        return f"{self.numerator}/{self.denominator}"


# ═══════════════════════════════════════════════════════════════
# Time Signature Enum
# ═══════════════════════════════════════════════════════════════

class TimeSignature(Enum):
    """Common time signatures including additive/complex meters."""

    # Simple duple (beats in 2's)
    two_two = TimeSignatureValue(2, 2)  # cut time
    two_four = TimeSignatureValue(2, 4)  # march
    two_eight = TimeSignatureValue(2, 8)  # fast 2/8

    # Simple triple (beats in 3's)
    three_two = TimeSignatureValue(3, 2)  # slow 3/2
    three_four = TimeSignatureValue(3, 4)  # waltz
    three_eight = TimeSignatureValue(3, 8)  # fast waltz

    # Simple quadruple (beats in 4's)
    four_two = TimeSignatureValue(4, 2)  # slow 4/2
    four_four = TimeSignatureValue(4, 4)  # common time
    four_eight = TimeSignatureValue(4, 8)  # fast 4/8

    # Compound duple (2 beats, each divided into 3)
    six_eight = TimeSignatureValue(6, 8)  # 2 beats of 3
    six_four = TimeSignatureValue(6, 4)  # 2 beats of 3 (slow)

    # Compound triple (3 beats, each divided into 3)
    nine_eight = TimeSignatureValue(9, 8)  # 3 beats of 3
    nine_four = TimeSignatureValue(9, 4)  # 3 beats of 3 (slow)

    # Compound quadruple (4 beats, each divided into 3)
    twelve_eight = TimeSignatureValue(12, 8)  # 4 beats of 3
    twelve_four = TimeSignatureValue(12, 4)  # 4 beats of 3 (slow)

    # Additive meters - 5 beats (2+3 or 3+2)
    five_eight_23 = TimeSignatureValue(5, 8, (2, 3))  # 2+3
    five_eight_32 = TimeSignatureValue(5, 8, (3, 2))  # 3+2
    five_four_23 = TimeSignatureValue(5, 4, (2, 3))  # 2+3 (slow)
    five_four_32 = TimeSignatureValue(5, 4, (3, 2))  # 3+2 (slow)

    # Additive meters - 7 beats (2+2+3, 2+3+2, 3+2+2)
    seven_eight_223 = TimeSignatureValue(7, 8, (2, 2, 3))
    seven_eight_232 = TimeSignatureValue(7, 8, (2, 3, 2))
    seven_eight_322 = TimeSignatureValue(7, 8, (3, 2, 2))
    seven_four_223 = TimeSignatureValue(7, 4, (2, 2, 3))
    seven_four_232 = TimeSignatureValue(7, 4, (2, 3, 2))
    seven_four_322 = TimeSignatureValue(7, 4, (3, 2, 2))

    # Additive meters - 8 beats (3+3+2, 3+2+3, 2+3+3)
    eight_eight_332 = TimeSignatureValue(8, 8, (3, 3, 2))
    eight_eight_323 = TimeSignatureValue(8, 8, (3, 2, 3))
    eight_eight_233 = TimeSignatureValue(8, 8, (2, 3, 3))

    # Additive meters - 10 beats (2+2+3+3, 3+3+2+2, 2+3+2+3)
    ten_eight_2233 = TimeSignatureValue(10, 8, (2, 2, 3, 3))
    ten_eight_3322 = TimeSignatureValue(10, 8, (3, 3, 2, 2))
    ten_eight_2323 = TimeSignatureValue(10, 8, (2, 3, 2, 3))

    # Additive meters - 11 beats (2+2+2+2+3, etc.)
    eleven_eight_22223 = TimeSignatureValue(11, 8, (2, 2, 2, 2, 3))
    eleven_eight_32222 = TimeSignatureValue(11, 8, (3, 2, 2, 2, 2))

    # Additive meters - 13 beats (2+2+3+3+3, etc.)
    thirteen_eight_22333 = TimeSignatureValue(13, 8, (2, 2, 3, 3, 3))
    thirteen_eight_33322 = TimeSignatureValue(13, 8, (3, 3, 3, 2, 2))

    # Common alternative names
    common = TimeSignatureValue(4, 4)
    cut = TimeSignatureValue(2, 2)

    @property
    def numerator(self) -> int:
        return self.value.numerator

    @property
    def denominator(self) -> int:
        return self.value.denominator

    @property
    def beats_per_bar(self) -> int:
        return self.value.beats_per_bar

    @property
    def subdivisions(self) -> Optional[Tuple[int, ...]]:
        return self.value.subdivisions

    @property
    def beat_grouping(self) -> List[int]:
        """Get beat grouping in terms of 2's and 3's."""
        return self.value.beat_grouping

    @property
    def subdivision_grouping(self) -> List[int]:
        """Get detailed subdivision grouping in terms of 2's and 3's."""
        return self.value.subdivision_grouping

    @property
    def display_name(self) -> str:
        """Get display name (e.g., '4/4', '6/8', '5/8(2+3)')."""
        base = f"{self.numerator}/{self.denominator}"
        if self.value.is_additive:
            grouping = "+".join(str(s) for s in self.subdivisions)
            return f"{base}[{grouping}]"
        return base

    @property
    def beat_count_type(self) -> str:
        return self.value.beat_count_type

    @property
    def is_simple(self) -> bool:
        return self.value.is_simple

    @property
    def is_compound(self) -> bool:
        return self.value.is_compound

    @property
    def is_complex(self) -> bool:
        return self.value.is_complex

    @property
    def notation(self) -> str:
        return self.value.notation

    def to_lilypond(self) -> str:
        """Export to LilyPond format."""
        return f"\\time {self.numerator}/{self.denominator}"

    def to_meter_string(self) -> str:
        """Format as 'M(5/8[2+3])' style."""
        if self.value.is_additive:
            grouping = "+".join(str(s) for s in self.subdivisions)
            return f"M({self.numerator}/{self.denominator}[{grouping}])"
        return f"M({self.numerator}/{self.denominator})"

    @classmethod
    def get(cls, name: str) -> Optional['TimeSignature']:
        """Get time signature by name."""
        try:
            return cls[name.lower()]
        except KeyError:
            return None

    @classmethod
    def from_numbers(cls, numerator: int, denominator: int,
                     subdivisions: Optional[Tuple[int, ...]] = None) -> Optional['TimeSignature']:
        """Get time signature by numerator and denominator (and optional subdivisions)."""
        for ts in cls:
            if ts.numerator == numerator and ts.denominator == denominator:
                if subdivisions is None and ts.subdivisions is None:
                    return ts
                if subdivisions and ts.subdivisions and tuple(subdivisions) == ts.subdivisions:
                    return ts
        return None

    @classmethod
    def from_string(cls, s: str) -> Optional['TimeSignature']:
        """Parse time signature from string like 'M(5/8)', '5/8', or '5/8[2+3]'."""
        # Handle format "M(5/8)"
        if s.startswith("M(") and s.endswith(")"):
            s = s[2:-1]

        # Handle format "5/8[2+3]"
        if "[" in s:
            time_part, grouping_part = s.split("[")
            grouping_part = grouping_part.rstrip("]")
            subdivisions = tuple(int(x) for x in grouping_part.split("+"))
            num, den = map(int, time_part.split("/"))
            return cls.from_numbers(num, den, subdivisions)

        # Handle simple "5/8"
        if "/" in s:
            num, den = map(int, s.split("/"))
            return cls.from_numbers(num, den)

        # Try by name
        return cls.get(s)


# ═══════════════════════════════════════════════════════════════
# PPQ (Pulses Per Quarter Note)
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class PPQValue:
    """PPQ (Pulses Per Quarter note) resolution."""
    ppq: int
    name: str
    description: str = ""

    def __post_init__(self):
        if self.ppq <= 0:
            raise ValueError(f"PPQ must be positive, got {self.ppq}")


class PPQ(Enum):
    """MIDI PPQ (Pulses Per Quarter note) resolutions."""
    low = PPQValue(114, "Low", "2⁴ × 3³ = 114")
    standard = PPQValue(432, "Standard", "2⁴ × 3³ = 432")
    high = PPQValue(864, "High", "2⁵ × 3³ = 864")

    @property
    def ppq(self) -> int:
        return self.value.ppq

    @property
    def display_name(self) -> str:
        return self.value.name

    @classmethod
    def get(cls, name: str) -> Optional['PPQ']:
        """Get PPQ by name."""
        try:
            return cls[name.lower()]
        except KeyError:
            return None

    @classmethod
    def from_ppq(cls, ppq: int) -> Optional['PPQ']:
        """Get PPQ by numeric value."""
        for p in cls:
            if p.ppq == ppq:
                return p
        return None


# ═══════════════════════════════════════════════════════════════
# Sample Rate
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class SampleRateValue:
    """Audio sample rate in Hz."""
    rate: int
    name: str
    description: str = ""


class SampleRate(Enum):
    """Audio sample rates."""
    cd = SampleRateValue(44100, "CD", "44.1 kHz - Compact Disc")
    dvd = SampleRateValue(48000, "DVD", "48 kHz - DVD Video")
    high = SampleRateValue(96000, "High", "96 kHz - High Resolution")
    studio = SampleRateValue(192000, "Studio", "192 kHz - Studio Quality")

    @property
    def rate(self) -> int:
        return self.value.rate

    @property
    def khz(self) -> float:
        """Sample rate in kHz."""
        return self.rate / 1000

    @property
    def display_name(self) -> str:
        return self.value.name

    @classmethod
    def get(cls, name: str) -> Optional['SampleRate']:
        """Get sample rate by name."""
        try:
            return cls[name.lower()]
        except KeyError:
            return None

    @classmethod
    def from_rate(cls, rate: int) -> Optional['SampleRate']:
        """Get sample rate by numeric value."""
        for sr in cls:
            if sr.rate == rate:
                return sr
        return None


# ═══════════════════════════════════════════════════════════════
# Bit Depth
# ═══════════════════════════════════════════════════════════════

class BitDepth(Enum):
    """Audio bit depths."""
    depth_16 = 16
    depth_24 = 24
    depth_32 = 32

    def __int__(self) -> int:
        return self.value

    @property
    def bits(self) -> int:
        return self.value

    @property
    def max_value(self) -> int:
        """Maximum integer value for this bit depth."""
        return (1 << (self.value - 1)) - 1

    @property
    def min_value(self) -> int:
        """Minimum integer value for this bit depth."""
        return -(1 << (self.value - 1))

    @classmethod
    def get(cls, depth: int) -> Optional['BitDepth']:
        """Get bit depth by value."""
        try:
            return cls[f"depth_{depth}"]
        except KeyError:
            return None


# ═══════════════════════════════════════════════════════════════
# Audio Channels
# ═══════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class ChannelConfigValue:
    """Audio channel configuration."""
    count: int
    name: str
    description: str = ""


class AudioChannels(Enum):
    """Audio channel configurations."""
    mono = ChannelConfigValue(1, "Mono", "Single channel")
    stereo = ChannelConfigValue(2, "Stereo", "Left and Right")
    quad = ChannelConfigValue(4, "Quad", "Four corners")
    surround_51 = ChannelConfigValue(6, "5.1 Surround", "Front L/R, Center, Rear L/R, LFE")
    surround_71 = ChannelConfigValue(8, "7.1 Surround", "Front L/R, Center, Side L/R, Rear L/R, LFE")

    @property
    def count(self) -> int:
        return self.value.count

    @property
    def display_name(self) -> str:
        return self.value.name

    @classmethod
    def get(cls, name: str) -> Optional['AudioChannels']:
        """Get channel config by name."""
        try:
            return cls[name.lower()]
        except KeyError:
            return None

    @classmethod
    def from_count(cls, count: int) -> Optional['AudioChannels']:
        """Get channel config by channel count."""
        for ch in cls:
            if ch.count == count:
                return ch
        return None


# ═══════════════════════════════════════════════════════════════
# Convenience Lookup Functions (Backward Compatible)
# ═══════════════════════════════════════════════════════════════

def get_time_signature(name: str) -> Optional[Tuple[int, int]]:
    """Get time signature as tuple (numerator, denominator)."""
    ts = TimeSignature.get(name)
    return (ts.numerator, ts.denominator) if ts else None


def get_ppq(name: str) -> int:
    """Get PPQ value by name."""
    p = PPQ.get(name)
    return p.ppq if p else 432


def get_sample_rate(name: str) -> int:
    """Get sample rate by name."""
    sr = SampleRate.get(name)
    return sr.rate if sr else 44100


def get_channel_count(name: str) -> int:
    """Get channel count by name."""
    ch = AudioChannels.get(name)
    return ch.count if ch else 2


def to_meter(s: str) -> Optional[TimeSignature]:
    """Parse time signature from string like 'M(5/8)', '5/8', or '5/8[2+3]'."""
    return TimeSignature.from_string(s)


# ═══════════════════════════════════════════════════════════════
# Backward Compatible Dictionaries
# ═══════════════════════════════════════════════════════════════

TIME_SIGNATURES = {ts.name: (ts.numerator, ts.denominator) for ts in TimeSignature if not ts.value.is_additive}
PPQ_DICT = {p.name: p.ppq for p in PPQ}
SAMPLE_RATES = {sr.name: sr.rate for sr in SampleRate}
BIT_DEPTH_LIST = [bd.bits for bd in BitDepth]
AUDIO_CHANNELS = {ch.name: ch.count for ch in AudioChannels}

# ═══════════════════════════════════════════════════════════════
# Main / Example Usage
# ═══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("═" * 60)
    print("Meters & Audio Data Examples (Beat Groupings in 2's and 3's)")
    print("═" * 60)

    print("\n🎵 Simple Meters:")
    for name in ["two_four", "three_four", "four_four"]:
        ts = TimeSignature.get(name)
        if ts:
            print(f"  {ts.display_name:8} - {ts.beats_per_bar} beat(s) of {ts.denominator}")
            print(f"      Beat grouping: {ts.beat_grouping}")

    # Compound meters
    print("\n🎵 Compound Meters (beat grouping = 3):")
    for name in ["six_eight", "nine_eight", "twelve_eight"]:
        ts = TimeSignature.get(name)
        if ts:
            print(f"  {ts.display_name:8} - {ts.beats_per_bar} beat(s) of dotted {ts.denominator}")
            print(f"      Beat grouping: {ts.beat_grouping}")

    # Additive meters
    print("\n🎵 Additive/Complex Meters (beat grouping = 2's and 3's):")
    additive_meters = [
        ("five_eight_23", "5/8 (2+3) - Balkan"),
        ("seven_eight_223", "7/8 (2+2+3) - Bulgarian"),
        ("eight_eight_332", "8/8 (3+3+2) - Turkish"),
        ("ten_eight_2233", "10/8 (2+2+3+3) - Greek"),
        ("eleven_eight_22223", "11/8 (2+2+2+2+3) - Complex"),
        ("thirteen_eight_22333", "13/8 (2+2+3+3+3) - Aksak"),
    ]

    for name, description in additive_meters:
        ts = TimeSignature.get(name)
        if ts:
            print(f"  {ts.display_name:12} - {ts.beats_per_bar} beats")
            print(f"      Beat grouping: {ts.beat_grouping} ({description})")

    # Beat grouping comparison
    print("\n📊 Beat Grouping Comparison:")
    test_meters = [
        ("2/4", TimeSignature.two_four),
        ("3/4", TimeSignature.three_four),
        ("4/4", TimeSignature.four_four),
        ("6/8", TimeSignature.six_eight),
        ("9/8", TimeSignature.nine_eight),
        ("5/8", TimeSignature.five_eight_23),
        ("7/8", TimeSignature.seven_eight_223),
        ("8/8", TimeSignature.eight_eight_332),
    ]

    print(f"  {'Meter':<10} {'Beats':<6} {'Beat Grouping':<15} {'Type':<12}")
    print(f"  {'-' * 10} {'-' * 6} {'-' * 15} {'-' * 12}")
    for name, ts in test_meters:
        grouping_str = "+".join(str(g) for g in ts.beat_grouping)
        print(f"  {name:<10} {ts.beats_per_bar:<6} {grouping_str:<15} {ts.beat_count_type:<12}")

    # String parsing
    print("\n🔍 String Parsing (to_meter):")
    for s in ["M(5/8)", "5/8[2+3]", "7/8[2+2+3]", "M(11/8[2+2+2+2+3])", "cut", "common"]:
        ts = to_meter(s)
        if ts:
            print(f"  '{s}' -> {ts.display_name}")
            if ts.value.is_additive:
                print(f"           Beat grouping: {ts.beat_grouping}")

    # LilyPond export
    print("\n🎼 LilyPond Export:")
    for ts in [TimeSignature.four_four, TimeSignature.six_eight, TimeSignature.five_eight_23]:
        print(f"  {ts.display_name} -> {ts.to_lilypond()}")

    # Meter string format
    print("\n📝 Meter String Format (to_meter_string):")
    for ts in [TimeSignature.four_four, TimeSignature.seven_eight_223, TimeSignature.eleven_eight_22223]:
        print(f"  {ts.display_name} -> {ts.to_meter_string()}")

    # Real-world examples
    print("\n🌍 Real-World Examples:")
    examples = [
        ("March", TimeSignature.two_four, [2]),
        ("Waltz", TimeSignature.three_four, [3]),
        ("Rock", TimeSignature.four_four, [4]),
        ("Jig", TimeSignature.six_eight, [3, 3]),
        ("Slip Jig", TimeSignature.nine_eight, [3, 3, 3]),
        ("Balkan", TimeSignature.seven_eight_223, [2, 2, 3]),
        ("Turkish", TimeSignature.eight_eight_332, [3, 3, 2]),
        ("Greek", TimeSignature.ten_eight_2233, [2, 2, 3, 3]),
    ]

    for name, ts, grouping in examples:
        print(
            f"  {name:12} - {ts.display_name:8} - Beat pattern: {' '.join('𝅘𝅥𝅮' * g if g == 2 else '𝅘𝅥𝅮' * 3 for g in grouping)}")

    print("\n✅ Done!")