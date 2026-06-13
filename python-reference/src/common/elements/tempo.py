# tempo.py
from fractions import Fraction
from typing import Union

million = 1_000_000
thousand = 1_000

class Tempo:
    duration: Fraction
    bpm: int

    def __init__(self, duration: Union[Fraction, int], bpm: int):
        if isinstance(duration, int):
            self.duration = Fraction(1, duration)
        else:
            self.duration = duration
        self.bpm = bpm
        # duration per whole note in milliseconds
        self._ms_per_whole = (self.duration.denominator * 60) // self.duration.numerator * thousand // self.bpm

    def duration_in_ms(self, duration: Fraction) -> int:
        return self._ms_per_whole * duration.numerator // duration.denominator

    def duration_in_seconds(self, duration: Fraction) -> int:
        return self.ms(duration) // thousand

    def __mul__(self, factor: float) -> "Tempo":
        return Tempo(self.duration, int(self.bpm * factor))

    def __truediv__(self, factor: float) -> "Tempo":
        return Tempo(self.duration, int(self.bpm / factor))

    def __add__(self, delta: int) -> "Tempo":
        return Tempo(self.duration, self.bpm + delta)

    def __sub__(self, other: Union[int, "Tempo"]) -> "Tempo":
        if isinstance(other, Tempo):
            return Tempo(self.duration, self.bpm - other.bpm)
        return Tempo(self.duration, self.bpm - other)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Tempo):
            return False
        return self._ms_per_whole == other._ms_per_whole

    def __hash__(self) -> int:
        return hash(self._ms_per_whole)

    def __str__(self) -> str:
        d = self.duration
        if d.numerator == 1:
            return f"{d.denominator}={self.bpm}"
        return f"{d.numerator}/{d.denominator}={self.bpm}"

    def to_lilypond(self) -> str:
        return f"\\tempo {self}"


def to_tempo(s: str) -> Tempo:
    parts = s.split("=")
    duration = Fraction(parts[0].replace("/", "/"))  # handles "1/4" or "4"
    return Tempo(duration, int(parts[1]))

