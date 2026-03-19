from dataclasses import dataclass


# ----------------------------------------------------------------
# Scale
# ----------------------------------------------------------------

class Scale:
    def __init__(self, name, intervals):
        self.name = name
        self.intervals = intervals
        self.pitches = [(0 + interval) for interval in self.intervals]

    def __str__(self):
        return self.name

    def upper(self, pitch: int) -> int:
        """Return the next scale tone above the given pitch."""
        pc = pitch % 12
        for step in self.pitches:
            if step > pc:
                return pitch + (step - pc)
        return pitch + (12 - pc + self.pitches[0])

    def lower(self, pitch: int) -> int:
        """Return the next scale tone below the given pitch."""
        pc = pitch % 12
        for step in reversed(self.pitches):
            if step < pc:
                return pitch - (pc - step)
        return pitch - (pc - self.pitches[-1] + 12)


# ----------------------------------------------------------------
# Key
# ----------------------------------------------------------------

@dataclass
class Key:
    accidental: int  # position on circle of fifths, -6..+6
    tonic: int   # pitch class 0..11
    name: str

    def __str__(self):
        return self.name


# ----------------------------------------------------------------
# KeyScale  (the "C major", "A minor" etc. concept)
# ----------------------------------------------------------------

@dataclass
class KeyScale:
    key: Key
    scale: Scale

    def pitches(self) -> list[int]:
        """Pitch classes of every degree in this key/scale combination."""
        # return [(self.key.tonic + interval) % 12 for interval in self.scale.intervals]
        return [(self.key.tonic + interval) for interval in self.scale.intervals]

    def relative(self, all_keys: list[Key]) -> "KeyScale":
        """
        Return the relative major/minor (same pitches, different tonic).
        For major: relative minor is degree 6 (index 5).
        For minor: relative major is degree 3 (index 2).
        """
        pitches = self.pitches()
        if self.scale.name == "major":
            relative_tonic = pitches[5]
            relative_scale_name = "natural_minor"
        elif self.scale.name == "natural_minor":
            relative_tonic = pitches[2]
            relative_scale_name = "major"
        else:
            raise ValueError(f"No relative defined for scale '{self.scale.name}'")

        relative_key = next(k for k in all_keys if k.tonic == relative_tonic)
        relative_scale = SCALES[relative_scale_name]
        return KeyScale(relative_key, relative_scale)

    def parallel(self, target_scale: Scale) -> "KeyScale":
        """Return the parallel scale (same tonic, different scale)."""
        return KeyScale(self.key, target_scale)

    def __str__(self):
        return f"{self.key} {self.scale}"


# ----------------------------------------------------------------
# Built-in scales
# ----------------------------------------------------------------

SCALES: dict[str, Scale] = {
    "major":         Scale("major",         [0, 2, 4, 5, 7, 9, 11]),
    "natural_minor": Scale("natural_minor", [0, 2, 3, 5, 7, 9, 10]),
    "harmonic_minor":Scale("harmonic_minor",[0, 2, 3, 5, 7, 8, 11]),
    "melodic_minor": Scale("melodic_minor", [0, 2, 3, 5, 7, 9, 11]),
    # --- modes ---
    "aeolian":        Scale("aeolian",      [0, 2, 3, 5, 7, 9, 10]),
    "dorian":        Scale("dorian",        [0, 2, 3, 5, 7, 9, 10]),
    "mixolydian":    Scale("mixolydian",    [0, 2, 4, 5, 7, 9, 10]),
    "phrygian":      Scale("phrygian",      [0, 1, 3, 5, 7, 8, 10]),
    "lydian":        Scale("lydian",        [0, 2, 4, 6, 7, 9, 11]),
    "locrian":       Scale("locrian",       [0, 1, 3, 5, 6, 8, 10]),
# --- hypo modes ---
    "hypoionian":     Scale("hypoionian",     [0, 2, 4, 6, 7, 9, 11]),  # = Lydian
    "hypodorian":     Scale("hypodorian",     [0, 2, 4, 5, 7, 9, 10]),  # = Mixolydian
    "hypophrygian":   Scale("hypophrygian",   [0, 2, 3, 5, 7, 8, 10]),  # = Aeolian
    "hypolydian":     Scale("hypolydian",     [0, 1, 3, 5, 6, 8, 10]),  # = Locrian
    "hypomixolydian": Scale("hypomixolydian", [0, 2, 3, 5, 7, 9, 10]),  # = Dorian
    "hypoaeolian":    Scale("hypoaeolian",    [0, 1, 3, 5, 7, 8, 10]),  # = Phrygian
    "hypolocrian":    Scale("hypolocrian",    [0, 2, 3, 5, 7, 8, 10]),  # = Aeolian
    # --- pentatonic ---
    "pentatonic_major":    Scale("pentatonic_major",    [0, 2, 4, 7, 9]),
    "pentatonic_minor":    Scale("pentatonic_minor",    [0, 3, 5, 7, 10]),
    # --- blues ---
    "blues_major":         Scale("blues_major",         [0, 2, 3, 4, 7, 9]),
    "blues_minor":         Scale("blues_minor",         [0, 3, 5, 6, 7, 10]),
    # --- symmetric ---
    "whole_tone":          Scale("whole_tone",          [0, 2, 4, 6, 8, 10]),
    "diminished_hw":       Scale("diminished_hw",       [0, 1, 3, 4, 6, 7, 9, 10]),  # half-whole
    "diminished_wh":       Scale("diminished_wh",       [0, 2, 3, 5, 6, 8, 9, 11]),  # whole-half
    # --- other common ---
    "phrygian_dominant":   Scale("phrygian_dominant",   [0, 1, 4, 5, 7, 8, 10]),
    "hungarian_minor":     Scale("hungarian_minor",     [0, 2, 3, 6, 7, 8, 11]),
    "double_harmonic":     Scale("double_harmonic",     [0, 1, 4, 5, 7, 8, 11]),
    "bebop_dominant":      Scale("bebop_dominant",      [0, 2, 4, 5, 7, 9, 10, 11]),
    "bebop_major":         Scale("bebop_major",         [0, 2, 4, 5, 7, 8, 9, 11]),
}

# object Kurd : Diatonic(listOf(1, 2, 2, 2, 1, 2, 2))
# object Gypsy : Diatonic(listOf(1, 3, 1, 2, 1, 3, 1))
# object AhavohRabboh : Diatonic(listOf(1, 3, 1, 2, 1, 2, 2))
# object Hungarian : Diatonic(listOf(2, 1, 3, 1, 1, 3, 1))
# object Charhargah : Diatonic(listOf(1, 3, 1, 2, 1, 3, 1))
# object Spanish : Diatonic(listOf(1, 3, 1, 2, 1, 2, 2))

# ----------------------------------------------------------------
# All keys, ordered by circle of fifths
# ----------------------------------------------------------------

KEYS: dict[str, Key] = {
    "Gb": Key(-6,  6,  "Gb"),
    "Db": Key(-5,  1,  "Db"),
    "Ab": Key(-4,  8,  "Ab"),
    "Eb": Key(-3,  3,  "Eb"),
    "Bb": Key(-2, 10,  "Bb"),
    "F":  Key(-1,  5,  "F"),
    "C":  Key( 0,  0,  "C"),
    "G":  Key( 1,  7,  "G"),
    "D":  Key( 2,  2,  "D"),
    "A":  Key( 3,  9,  "A"),
    "E":  Key( 4,  4,  "E"),
    "B":  Key( 5, 11,  "B"),
    "F#": Key( 6,  6,  "F#"),
}

def circle_of_fifths() -> list[Key]:
    alist = list(KEYS.values())
    return alist [6:] + alist[:6]


# ----------------------------------------------------------------
# Main
# ----------------------------------------------------------------

def main():

    # Look up keys by name
    # keys_by_name = {k[name]: k for k in KEYS}

    c_major = KeyScale(KEYS["C"], SCALES["major"])
    print(c_major)                   # → C major
    print(c_major.pitches())         # → [0, 2, 4, 5, 7, 9, 11]

    a_minor = c_major.relative(list(KEYS.values()))
    print(a_minor)                   # → A natural_minor
    print(a_minor.pitches())         # → [9, 11, 0, 2, 4, 5, 7]

    c_minor = c_major.parallel(SCALES["natural_minor"])
    print(c_minor)                   # → C natural_minor

    print("\nCircle of fifths:")
    for key in circle_of_fifths():
        print(f"  {key.accidental}  {key.name}")


if __name__ == "__main__":
    main()
