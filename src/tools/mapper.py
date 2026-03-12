class Mapper:
    def __init__(self, input_range, output_range):
        self.input_min, self.input_max = input_range
        self.output_min, self.output_max = output_range
        self.output_type = type(self.output_min)

    def map(self, value):
        if value < self.input_min or value > self.input_max:
            raise ValueError(f"Value {value} outside range [{self.input_min}, {self.input_max}]")

        if self.input_max == self.input_min:
            mapped = self.output_min
        else:
            norm = (value - self.input_min) / (self.input_max - self.input_min)
            mapped = self.output_min + norm * (self.output_max - self.output_min)

        return int(round(mapped)) if self.output_type == int else float(mapped)


class NoteMapper:
    """Maps note names to numbers based on a key context."""

    def __init__(self, key='C'):
        # Normalize key: Bf -> Bb, F# -> Fs, etc.
        self.key = key.replace('f', 'b').replace('#', 's')

        # Note mappings
        self.notes = {'C': 0, 'Cs': 1, 'Db': 1, 'D': 2, 'Ds': 3, 'Eb': 3, 'E': 4, 'F': 5,
                      'Fs': 6, 'Gb': 6, 'G': 7, 'Gs': 8, 'Ab': 8, 'A': 9, 'As': 10, 'Bb': 10, 'B': 11}

        # Circle of fifths for key signatures
        self.keys = {'C': 0, 'G': 1, 'D': 2, 'A': 3, 'E': 4, 'B': 5, 'Fs': 6, 'Cs': 7,
                     'F': -1, 'Bb': -2, 'Eb': -3, 'Ab': -4, 'Db': -5, 'Gb': -6, 'Cb': -7}

        self.key_fifths = self.keys.get(self.key, 0)

    def __getitem__(self, note):
        """Get note number using dictionary-like syntax."""
        # Normalize note
        note = note.replace('f', 'b').replace('#', 's')

        if note not in self.notes:
            raise KeyError(f"Invalid note name: {note}")

        num = self.notes[note]

        # In sharp keys, prefer sharp spellings; in flat keys, prefer flat spellings
        if self.key_fifths > 0 and 'b' in note:
            for name, val in self.notes.items():
                if val == num and 's' in name:
                    return val
        elif self.key_fifths < 0 and 's' in note:
            for name, val in self.notes.items():
                if val == num and 'b' in name:
                    return val

        return num

    def get(self, note, default=None):
        """Get note number with default if not found."""
        try:
            return self[note]
        except KeyError:
            return default


# Usage examples
if __name__ == "__main__":
    # Create mappers for different keys
    bf_major = NoteMapper('Bf')  # Bb major
    g_major = NoteMapper('G')  # G major
    c_major = NoteMapper('C')  # C major

    # Use like a dictionary
    print("In Bb major:")
    print(f" B → {bf_major['B']}")  # 10 (Bb)
    print(f" Bf → {bf_major['Bf']}")  # 10 (Bb)
    print(f" E → {bf_major['E']}")  # 3 (Eb)
    print(f" Ef → {bf_major['Ef']}")  # 3 (Eb)

    print("\nIn G major:")
    print(f" F → {g_major['F']}")  # 6 (F#)
    print(f" F# → {g_major['F#']}")  # 6 (F#)

    print("\nIn C major:")
    print(f" B → {c_major['B']}")  # 11 (B natural)
    print(f" Bb → {c_major['Bb']}")  # 10 (Bb)

    # Using get() method
    print(f"\nInvalid note: {bf_major.get('X', 'Not found')}")
