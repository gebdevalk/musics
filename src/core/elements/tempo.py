from tools.ratio import Ratio
from typing import Union

million = 1_000_000
thousand = 1_000

class Tempo:
    duration: Ratio
    bpm: int

    def __init__(self, duration: Union[Ratio, int], bpm: int):
        if isinstance(duration, int):
            self.duration = Ratio(1, duration)
        else:
            self.duration = duration
        self.bpm = bpm
        self._dpwl = (self.duration.denominator * 60) // self.duration.numerator * thousand // self.bpm

    def ms(self, duration: Ratio) -> int:
        return self._dpwl * duration.numerator // duration.denominator

    def micros(self, duration: Ratio) -> int:
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
        return self._dpwl == other._dpwl

    def __hash__(self) -> int:
        return hash(self._dpwl)

    def __str__(self) -> str:
        d = self.duration
        if d.numerator == 1:
            return f"{d.denominator}={self.bpm}"
        return f"{d.numerator}/{d.denominator}={self.bpm}"

    def to_lilypond(self) -> str:
        return f"\\tempo {self}"


def to_tempo(s: str) -> Tempo:
    parts = s.split("=")
    duration = Ratio(parts[0].replace("/", "/"))  # handles "1/4" or "4"
    return Tempo(duration, int(parts[1]))


tempo_map = {
    "Larghissimo":      24,
    "Adagissimo":       24,
    "Grave":            35,
    "Largo":            50,
    "Lent":             52,
    "Lento":            52,
    "Larghetto":        63,
    "Adagio":           71,
    "Adagietto":        76,
    "Andante":          92,
    "Andantino":        94,
    "Marcia moderato":  84,
    "Andante moderato": 102,
    "Moderato":         114,
    "Allegretto":       116,
    "Allegro moderato": 118,
    "Allegro":          138,
    "Vivace":           166,
    "Vivacissimo":      174,
    "Allegrissimo":     174,
    "Allegro vivace":   174,
    "Presto":           184,
    "Prestissimo":      200,
}
