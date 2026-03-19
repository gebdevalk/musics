# --------------------------------------------------------
# Note names
# --------------------------------------------------------

NOTE_NAMES_SHARP = ["c", "c#", "d", "d#", "e", "f",
                    "f#", "g", "g#", "a", "a#", "b"]

NOTE_NAMES_FLAT = ["c", "db", "d", "eb", "e", "f",
                   "gb", "g", "ab", "a", "bb", "b"]

def pitch_to_name(pitch: int, prefer_sharps=True) -> str:
    """
    Convert MIDI pitch number → note name (lowercase).
    Example: 60 → "c4"
    """
    octave = (pitch // 12) - 1
    pc = pitch % 12
    name = NOTE_NAMES_SHARP[pc] if prefer_sharps else NOTE_NAMES_FLAT[pc]
    return f"{name}{octave}"

def name_to_pitch(name: str) -> int:
    """
    Convert note name → MIDI pitch number.
    Accepts lowercase: c4, f#3, eb5, etc.
    """
    name = name.strip().lower()

    # split into pitch class + octave
    if name[-2].isdigit():  # e.g. "c4"
        pc_name = name[:-1]
        octave = int(name[-1])
    else:  # e.g. "f#3", "eb5"
        pc_name = name[:-1]
        octave = int(name[-1])

    # find pitch class
    if pc_name in NOTE_NAMES_SHARP:
        pc = NOTE_NAMES_SHARP.index(pc_name)
    elif pc_name in NOTE_NAMES_FLAT:
        pc = NOTE_NAMES_FLAT.index(pc_name)
    else:
        raise ValueError(f"Unknown pitch name: {name}")

    return (octave + 1) * 12 + pc


