# common/elements/cycle_of_fifths.py

from common.elements.key_scale import Key

class COF:
    """Circle of Fifths utilities for key modulation and transposition."""

    # Your keys in circle of fifths order (starting from 6 flats to 6 sharps)
    _keys_in_fifth_order = [Key.GES, Key.DES, Key.AS, Key.ES, Key.BES, Key.F,
                            Key.C, Key.G, Key.D, Key.A, Key.E, Key.B, Key.FIS]

    # Steps to fifths mapping (semitone steps to circle of fifths movement)
    _steps_to_fifths_map = [0, -5, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5]

    @classmethod
    def _cyclic_index(cls, index: int) -> Key:
        """Return key at cyclic index from the fifth order list."""
        return cls._keys_in_fifth_order[index % len(cls._keys_in_fifth_order)]

    @classmethod
    def modulate(cls, signature: Key, delta: int) -> Key:
        """Move delta steps around the circle of fifths."""
        current_idx = cls._keys_in_fifth_order.index(signature)
        return cls._cyclic_index(current_idx + delta)

    @classmethod
    def transpose(cls, signature: Key, delta: int) -> Key:
        """Transpose by delta semitones using circle of fifths."""
        # Get the fifths movement for the given semitone delta
        fifth_delta = cls._steps_to_fifths_map[delta % len(cls._steps_to_fifths_map)]
        return cls.modulate(signature, fifth_delta)

    @classmethod
    def fifths_up(cls, signature: Key, steps: int = 1) -> Key:
        """Move steps clockwise around circle of fifths (add sharps)."""
        return cls.modulate(signature, steps)

    @classmethod
    def fifths_down(cls, signature: Key, steps: int = 1) -> Key:
        """Move steps counter-clockwise around circle of fifths (add flats)."""
        return cls.modulate(signature, -steps)

    @classmethod
    def distance(cls, from_key: Key, to_key: Key) -> int:
        """Calculate number of fifths between two keys."""
        from_idx = cls._keys_in_fifth_order.index(from_key)
        to_idx = cls._keys_in_fifth_order.index(to_key)
        return (to_idx - from_idx) % len(cls._keys_in_fifth_order)

if __name__ == '__main__':
    # Test the circle of fifths
    print(COF.modulate(Key.C, 1))   # Key.G (up a fifth)
    print(COF.modulate(Key.C, -1))  # Key.F (down a fifth)
    print(COF.modulate(Key.C, 2))   # Key.D (up two fifths)

    # Transpose by semitones
    print(COF.transpose(Key.C, 2))  # Up a whole step (should go to D)
    print(COF.transpose(Key.C, -2)) # Down a whole step (should go to Bb)

    # Helper methods
    print(COF.fifths_up(Key.C, 1))    # Key.G
    print(COF.fifths_up(Key.C, 2))    # Key.D
    print(COF.fifths_down(Key.C, 1))  # Key.F

    # Distance between keys
    print(COF.distance(Key.C, Key.G))  # 1 (one fifth)
    print(COF.distance(Key.C, Key.F))  # 1 (one fifth down, represented as 11 or 1 depending on direction)