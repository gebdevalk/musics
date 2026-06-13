"""
Unified General MIDI instrument mapper — short-name and long-name lookup.
"""


class MidiMapper:
    """General MIDI instrument lookup with short and long name support."""

    # Shortest unique instrument names (GM program numbers 0-127)
    GM_SHORT = {
        # Pianos 0-7
        "piano": 0, "bpiano": 1, "epiano": 2, "hky": 3, "ep1": 4, "ep2": 5,
        "hpsi": 6, "clav": 7,
        # Chromatic 8-15
        "cel": 8, "glock": 9, "mbox": 10, "vibe": 11, "mari": 12, "xylo": 13,
        "tbell": 14, "dulc": 15,
        # Organs 16-23
        "org1": 16, "org2": 17, "org3": 18, "org4": 19, "org5": 20, "acc": 21,
        "harm": 22, "tang": 23,
        # Guitars 24-31
        "gtrn": 24, "gtrs": 25, "gtrj": 26, "gtrc": 27, "gtrm": 28, "odgt": 29,
        "dist": 30, "harmonics": 31,
        # Basses 32-39
        "bass": 32, "ebass": 33, "ebass2": 34, "fret": 35, "slap1": 36, "slap2": 37,
        "synb1": 38, "synb2": 39,
        # Strings 40-47
        "vn": 40, "va": 41, "vc": 42, "cb": 43, "trem": 44, "pizz": 45,
        "hp": 46, "tim": 47,
        # Ensembles 48-55
        "str1": 48, "str2": 49, "syns1": 50, "syns2": 51, "choir": 52, "voice": 53,
        "synv": 54, "orch": 55,
        # Brass 56-63
        "tp": 56, "tb": 57, "tuba": 58, "mtp": 59, "fh": 60, "brass": 61,
        "synbr1": 62, "synbr2": 63,
        # Sax 64-71
        "ssax": 64, "asax": 65, "tsax": 66, "bsax": 67, "ob": 68, "eh": 69,
        "bn": 70, "cl": 71,
        # Woodwinds 72-79
        "pic": 72, "fl": 73, "rec": 74, "pan": 75, "bottle": 76, "shak": 77,
        "whis": 78, "ocar": 79,
        # Leads 80-87
        "lead1": 80, "lead2": 81, "lead3": 82, "lead4": 83, "lead5": 84, "lead6": 85,
        "lead7": 86, "lead8": 87,
        # Pads 88-95
        "pad1": 88, "pad2": 89, "pad3": 90, "pad4": 91, "pad5": 92, "pad6": 93,
        "pad7": 94, "pad8": 95,
        # FX 96-103
        "fx1": 96, "fx2": 97, "fx3": 98, "fx4": 99, "fx5": 100, "fx6": 101,
        "fx7": 102, "fx8": 103,
        # Ethnic 104-111
        "sitar": 104, "banjo": 105, "sham": 106, "koto": 107, "kalim": 108, "bag": 109,
        "fiddle": 110, "shan": 111,
        # Perc 112-119
        "tink": 112, "agogo": 113, "steel": 114, "wood": 115, "taiko": 116, "tom": 117,
        "synd": 118, "rev": 119,
        # SFX 120-127
        "fretnoise": 120, "breath": 121, "sea": 122, "bird": 123, "phone": 124, "heli": 125,
        "appl": 126, "gun": 127,
    }

    # Full instrument names (General MIDI)
    GM_LONG = [
        "Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano", "Honky-tonk Piano",
        "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavinet",
        "Celesta", "Glockenspiel", "Music Box", "Vibraphone",
        "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
        "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ",
        "Reed Organ", "Accordion", "Harmonica", "Tango Accordion",
        "Acoustic Guitar (nylon)", "Acoustic Guitar (steel)", "Electric Guitar (jazz)", "Electric Guitar (clean)",
        "Electric Guitar (muted)", "Overdriven Guitar", "Distortion Guitar", "Guitar harmonics",
        "Acoustic Bass", "Electric Bass (finger)", "Electric Bass (pick)", "Fretless Bass",
        "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
        "Violin", "Viola", "Cello", "Contrabass",
        "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
        "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2",
        "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
        "Trumpet", "Trombone", "Tuba", "Muted Trumpet",
        "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
        "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax",
        "Oboe", "English Horn", "Bassoon", "Clarinet",
        "Piccolo", "Flute", "Recorder", "Pan Flute",
        "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
        "Lead 1 (square)", "Lead 2 (sawtooth)", "Lead 3 (calliope)", "Lead 4 (chiff)",
        "Lead 5 (charang)", "Lead 6 (voice)", "Lead 7 (fifths)", "Lead 8 (bass + lead)",
        "Pad 1 (new age)", "Pad 2 (warm)", "Pad 3 (polysynth)", "Pad 4 (choir)",
        "Pad 5 (bowed)", "Pad 6 (metallic)", "Pad 7 (halo)", "Pad 8 (sweep)",
        "FX 1 (rain)", "FX 2 (soundtrack)", "FX 3 (crystal)", "FX 4 (atmosphere)",
        "FX 5 (brightness)", "FX 6 (goblins)", "FX 7 (echoes)", "FX 8 (sci-fi)",
        "Sitar", "Banjo", "Shamisen", "Koto",
        "Kalimba", "Bag pipe", "Fiddle", "Shanai",
        "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock",
        "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
        "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet",
        "Telephone Ring", "Helicopter", "Applause", "Gunshot",
    ]

    # GM drum map (channel 10, notes 35-81)
    GM_DRUMS = {
        35: "Acoustic Bass Drum", 36: "Bass Drum 1", 37: "Side Stick",
        38: "Acoustic Snare", 39: "Hand Clap", 40: "Electric Snare",
        41: "Low Floor Tom", 42: "Closed Hi-Hat", 43: "High Floor Tom",
        44: "Pedal Hi-Hat", 45: "Low Tom", 46: "Open Hi-Hat",
        47: "Low-Mid Tom", 48: "Hi-Mid Tom", 49: "Crash Cymbal 1",
        50: "High Tom", 51: "Ride Cymbal 1", 52: "Chinese Cymbal",
        53: "Ride Bell", 54: "Tambourine", 55: "Splash Cymbal",
        56: "Cowbell", 57: "Crash Cymbal 2", 58: "Vibraslap",
        59: "Ride Cymbal 2", 60: "Hi Bongo", 61: "Low Bongo",
        62: "Mute Hi Conga", 63: "Open Hi Conga", 64: "Low Conga",
        65: "High Timbale", 66: "Low Timbale", 67: "High Agogo",
        68: "Low Agogo", 69: "Cabasa", 70: "Maracas",
        71: "Short Whistle", 72: "Long Whistle", 73: "Short Guiro",
        74: "Long Guiro", 75: "Claves", 76: "Hi Wood Block",
        77: "Low Wood Block", 78: "Mute Cuica", 79: "Open Cuica",
        80: "Mute Triangle", 81: "Open Triangle",
    }

    def __init__(self):
        self._long_to_num = {name.lower(): i for i, name in enumerate(self.GM_LONG)}
        self._num_to_short = {v: k for k, v in self.GM_SHORT.items()}

    def __call__(self, name: str) -> int:
        """Look up by short name. e.g. mapper('fl') → 73"""
        return self.GM_SHORT[name.lower()]

    def short_name(self, program: int) -> str:
        """Get short name from program number. e.g. mapper.short_name(73) → 'fl'"""
        return self._num_to_short.get(program, f"unknown_{program}")

    def long_name(self, program: int) -> str:
        """Get full name from program number."""
        if 0 <= program < len(self.GM_LONG):
            return self.GM_LONG[program]
        return f"Unknown Program {program}"

    def from_long(self, name: str) -> int:
        """Look up by long name (case-insensitive partial match)."""
        name = name.lower().strip()

        # Exact match first
        if name in self._long_to_num:
            return self._long_to_num[name]

        # Partial match
        matches = [(n, num) for n, num in self._long_to_num.items() if name in n]
        if len(matches) == 1:
            return matches[0][1]
        elif len(matches) > 1:
            suggestions = [f"'{n}' ({num})" for n, num in matches[:5]]
            raise ValueError(f"Multiple matches for '{name}': {', '.join(suggestions)}")
        raise ValueError(f"Instrument '{name}' not found")

    def drum_name(self, note: int) -> str:
        """Get drum sound name for a note on channel 10."""
        return self.GM_DRUMS.get(note, f"Unknown drum note {note}")

    def find(self, partial: str) -> dict:
        """Find short names containing the partial string."""
        partial = partial.lower()
        return {k: v for k, v in self.GM_SHORT.items() if partial in k}

    def list_instruments(self, start: int = 0, end: int = None):
        """List instruments in range with their program numbers."""
        end = end or len(self.GM_LONG)
        for i in range(start, min(end, len(self.GM_LONG))):
            short = self._num_to_short.get(i, "")
            print(f"{i:3d}: {short:8s} {self.GM_LONG[i]}")
