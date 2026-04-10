# maidi_data.py

# --------------------------------------------------------
# MidiNotes
# --------------------------------------------------------

class MidiNote:
    def __init__(self, channel, duration, delay, pitches, velocity, tied, program, cc_values=None):
        self.channel = channel  # int (0–15)
        self.duration = duration  # float or ticks
        self.delay = delay  # float
        self.pitches = pitches  # list[int]
        self.velocity = velocity  # int (0–127)
        self.tied = tied # False | True
        self.program = program  # int (0–127)
        self.cc_values = cc_values or {}  # dict[int, float or int]

    def __repr__(self):
        return (
            f"MidiNote(channel={self.channel}, duration={self.duration}, delay={self.delay}, "
            f"pitches={self.pitches}, velocity={self.velocity}, tied={self.tied}, "
            f"program={self.program}, cc_values={self.cc_values})"
        )


class MidiDrumNote:
    def __init__(self, timbre, duration, velocity):
        self.duration = duration  # float or ticks
        self.timbre = timbre
        self.velocity = velocity  # int (0–127)

    def __repr__(self):
        return (
            f"MidiDrumNote(duration={self.duration}, "
            f"timbre={self.timbre}, velocity={self.velocity})"
        )


class MidiNoteOn:
    def __init__(self, channel, pitches, velocity, program,
                 cc_values: list[tuple[int,int]]=None):
        self.channel = channel              # int (0–15)
        self.pitches = pitches              # tuple[int] or list[int]
        self.velocity = velocity            # int (0–127)
        self.program = program              # int (0–127)
        self.cc_values = cc_values or []    # list[tuple[int, int]]

    def __repr__(self):
        return (
            f"MidiNoteOn(channel={self.channel}, pitches={self.pitches}, "
            f"velocity={self.velocity}, "
            f"program={self.program}, cc_values={self.cc_values})"
        )


class MidiNoteOff:
    def __init__(self, channel, pitches):
        self.channel = channel              # int (0–15)
        self.pitches = pitches              # list[int]

    def __repr__(self):
        return f"MidiNote(channel={self.channel}, pitches={self.pitches}"
