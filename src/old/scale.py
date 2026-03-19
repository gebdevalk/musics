from typing import List


# ------------------------------------------------------------
# Utility functions (Kotlin equivalents)
# ------------------------------------------------------------

def shifted_left(lst: List[int], offset: int) -> List[int]:
    """Rotate a list left by offset (Kotlin's shiftedLeft)."""
    offset %= len(lst)
    return lst[offset:] + lst[:offset]


def to_scale(intervals: List[int]) -> List[int]:
    """
    Kotlin's List<Int>.toScale():
    Convert interval steps into cumulative pitch offsets.
    Example: [2,2,1] → [0,2,4]
    """
    accu = 0
    result = []
    for step in intervals:
        result.append(accu)
        accu += step
    return result


# ------------------------------------------------------------
# XScale (translation of Kotlin abstract class Scale)
# ------------------------------------------------------------

def normalize(s: str) -> str:
    return (
        s.replace("is", "#")
         .replace("es", "b")
         .replace("s", "b")
    )


class XScale:
    """
    Python translation of Kotlin's Scale class.
    Supports:
    - ascending & descending interval patterns
    - diatonic neighbors (upper/lower)
    - degree-to-pitch conversion
    - scale rotation
    """

    # Static interval definitions
    DIATONIC = [2, 2, 1, 2, 2, 2, 1]
    ASC_MELODIC_MINOR = [2, 1, 2, 2, 2, 2, 1]
    HARMONIC_MINOR = [2, 1, 2, 2, 1, 3, 1]

    def __init__(self, asc_intervals: List[int], desc_intervals: List[int] = None):
        if desc_intervals is None:
            desc_intervals = asc_intervals

        if len(asc_intervals) != len(desc_intervals):
            raise ValueError("Ascending and descending intervals must match")

        # Convert intervals to cumulative pitch offsets
        self.ascending = to_scale(asc_intervals)
        self.descending = to_scale(desc_intervals)

        # Extended versions for wrap-around
        self.ascending_ext = self.ascending + [x + 12 for x in self.ascending]
        self.descending_ext = self.descending + [x + 12 for x in self.descending]

        self.size = len(self.ascending)

        # State for degree2number
        self.current_index = 0
        self.previous_index = 0

        # Precompute scale degrees (i, ii, iii, … xv)
        for n in range(1, 16):
            setattr(self, self._roman(n), self.degree2number(n))

    # --------------------------------------------------------
    # Helpers
    # --------------------------------------------------------

    def _roman(self, n: int) -> str:
        numerals = [
            "i","ii","iii","iv","v","vi","vii",
            "viii","ix","x","xi","xii","xiii","xiv","xv"
        ]
        return numerals[n - 1]

    def delta_up(self, low: int, high: int) -> int:
        return self.ascending_ext[high] - self.ascending_ext[low]

    def delta_down(self, low: int, high: int) -> int:
        return self.descending_ext[low] - self.descending_ext[high]

    # --------------------------------------------------------
    # Diatonic neighbors
    # --------------------------------------------------------

    def upper(self, pitch: int) -> int:
        offset = pitch % 12
        return pitch + self.delta_up(offset, offset + 1)

    def lower(self, pitch: int) -> int:
        offset = pitch % 12
        return pitch - self.delta_down(offset, offset + 1)

    # --------------------------------------------------------
    # Degree → pitch conversion
    # --------------------------------------------------------

    def degree2number(self, degree: int) -> int:
        index = degree - 1
        self.current_index = index

        steps = self.ascending if index > self.previous_index else self.descending
        self.previous_index = index

        octave = index // self.size
        step = steps[index % self.size]

        return octave * 12 + step

    # --------------------------------------------------------
    # Utilities
    # --------------------------------------------------------

    def shift_left(self, offset: int):
        """Rotate ascending & descending intervals."""
        self.ascending = shifted_left(self.ascending, offset)
        self.descending = shifted_left(self.descending, offset)

    def __repr__(self):
        values = []
        for i in range(1, self.size + 2):
            values.append(str(self.degree2number(i)))

        if self.ascending != self.descending:
            for i in range(self.size, 0, -1):
                values.append(str(self.degree2number(i)))

        return f"XScale({values})"

class XPentatonic(XScale): pass
class XHexatonic(XScale): pass
class XDiatonic(XScale): pass


# Pentatonic
PentatonicMajor = XPentatonic([2, 2, 3, 2, 2])
PentatonicMinor = XPentatonic([2, 3, 2, 2, 2])
Balinese = XPentatonic([1, 2, 4, 1])

# Hexatonic
WholeTone = XHexatonic([2, 2, 2, 2, 2, 2])
MajorHexatonic = XHexatonic([2, 2, 1, 2, 2, 3])
MinorHexatonic = XHexatonic([2, 1, 2, 2, 3, 2])
Augmented = XHexatonic([3, 1, 3, 1, 3, 1])
Promethean = XHexatonic([2, 2, 2, 3, 1, 2])
Blues = XHexatonic([3, 2, 1, 1, 3, 2])
Tritone = XHexatonic([1, 3, 2, 1, 3, 2])
TwoSemitoneTritone = XHexatonic([1, 1, 4, 1, 1, 4])
Byzantine = XHexatonic([1, 3, 1, 2, 1, 4])

# Diatonic (Heptatonic)
Major = XDiatonic(XScale.DIATONIC)
Minor = XDiatonic(shifted_left(XScale.DIATONIC, 5))
HarmonicMinor = XDiatonic(XScale.HARMONIC_MINOR)
MelodicMinor = XDiatonic(XScale.ASC_MELODIC_MINOR, shifted_left(XScale.DIATONIC, 5))

Ionian = XDiatonic(XScale.DIATONIC)
Dorian = XDiatonic(shifted_left(XScale.DIATONIC, 1))
Phrygian = XDiatonic(shifted_left(XScale.DIATONIC, 2))
Lydian = XDiatonic(shifted_left(XScale.DIATONIC, 3))
Mixolydian = XDiatonic(shifted_left(XScale.DIATONIC, 4))
Aeolian = XDiatonic(shifted_left(XScale.DIATONIC, 5))
Locrian = XDiatonic(shifted_left(XScale.DIATONIC, 6))

# Chromatic
Chromatic = XScale([1] * 12)
