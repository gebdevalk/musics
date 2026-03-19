from core.domain.ornaments import Scale
from core.elements.pitch_names import pitch_to_name, name_to_pitch
from old.scale import XScale


class Key:
    def __init__(self, accidental: int, base: int, name: str, scale: Scale):
        self.scale        = scale
        self.accidental   = accidental
        self.tonic_pc     = base
        self.display_name = name
        self.lower_name   = name.lower()

    def name_of(self, pitch: int, prefer_sharps=True) -> str:
        return pitch_to_name(pitch, prefer_sharps)

    def pitch_of(self, name: str) -> int:
        return name_to_pitch(name)

    def degree(self, scale: XScale, n: int) -> int:
        return self.tonic_pc + scale.degree2number(n)

    def upper(self, scale: XScale, pitch: int) -> int:
        return scale.upper(pitch)

    def lower(self, scale: XScale, pitch: int) -> int:
        return scale.lower(pitch)

    def __str__(self):
        return self.display_name

    def __repr__(self):
        return f"Key({self.display_name!r})"


# class Keys(dict):
#     _instance = None
#
#     def __new__(cls):
#         if cls._instance is None:
#             cls._instance = super().__new__(cls)
#             cls._instance.update({
#                 "Gb": Key(-6,  6,  "Gb"),
#                 "Db": Key(-5,  1,  "Db"),
#                 "Ab": Key(-4,  8,  "Ab"),
#                 "Eb": Key(-3,  3,  "Eb"),
#                 "Bb": Key(-2, 10,  "Bb"),
#                 "F":  Key(-1,  5,  "F"),
#                 "C":  Key( 0,  0,  "C"),
#                 "G":  Key( 1,  7,  "G"),
#                 "D":  Key( 2,  2,  "D"),
#                 "A":  Key( 3,  9,  "A"),
#                 "E":  Key( 4,  4,  "E"),
#                 "B":  Key( 5, 11,  "B"),
#                 "F#": Key( 6,  6,  "F#"),
#             })
#         return cls._instance

def main():
    keys = Keys()
    print(keys["C"].tonic)       # → 0
    print(keys["C"].display_name)   # → "C"
    print(keys["F#"].accidental)    # → 6
    print(str(keys["Gb"]))          # → "Gb"
    print(Keys() is Keys())         # → True
if __name__ == "__main__":

    main()