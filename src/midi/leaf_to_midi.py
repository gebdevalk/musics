# leaf_to_midi.py

from typing import Any

from core.domain.leafs import Leaf, LeafOn, LeafOff
from tools.ratio import Ratio

CC_PANNING = 10

class MidiNote:
    def __init__(self, channel, duration, articulated, pitches, velocity, program, cc_values=None):
        self.channel = channel  # int (0–15)
        self.duration = duration  # float or ticks
        self.articulated = articulated  # float
        self.pitches = pitches  # list[int]
        self.velocity = velocity  # int (0–127)
        self.program = program  # int (0–127)
        self.cc_values = cc_values or {}  # dict[int, float or int]

    def __repr__(self):
        return (
            f"MidiNote(channel={self.channel}, duration={self.duration}, articulated={self.articulated}, "
            f"pitches={self.pitches}, velocity={self.velocity}, "
            f"program={self.program}, cc_values={self.cc_values})"
        )

def resolve_data(leaf: Leaf|LeafOn, time: float) -> tuple[
    int | Any, float | Any, int | Any, int | Any, float]:
    meta = leaf.parent
    volume = leaf.volume if leaf.volume is not None else meta.value("volume", time)
    dynamic = leaf.dynamic if leaf.dynamic is not None else meta.value("dynamic", time)
    articulation = leaf.articulation if leaf.articulation is not None else meta.value("articulation", time)
    transposition = leaf.transposition if leaf.transposition is not None else meta.value("transposition", time)
    timbre = leaf.timbre if leaf.timbre is not None else meta.value("timbre", time)
    panning = leaf.panning if leaf.panning is not None else meta.value("panning", time)

    # Calculate velocity
    velocity = round(1.27 * (volume + dynamic))
    # Clamp velocity to MIDI range
    velocity = max(0, min(127, velocity))

    # Calculate panning CC value
    panning = round(((panning + 1.0) / 2.0) * 127)
    panning = max(0, min(127, panning))

    return velocity, articulation, transposition, timbre, panning

def render_leaf(leaf: Leaf, channel: int, time: Ratio) -> MidiNote:
    velocity, articulation, transposition, timbre, panning = resolve_data(leaf, float(time))

    return MidiNote(channel=channel, duration=leaf.duration, articulated = leaf.duration*articulation,
                    pitches=[p + transposition for p in leaf.pitches], velocity=velocity, program=timbre,
                    cc_values={CC_PANNING: panning})


class MidiNoteOn:
    def __init__(self, channel, pitches, velocity, _, program, cc_values=None):
        self.channel = channel              # int (0–15)
        self.pitches = pitches              # list[int]
        self.velocity = velocity            # int (0–127)
        self.program = program              # int (0–127)
        self.cc_values = cc_values or {}    # dict[int, float or int]

    def __repr__(self):
        return (
            f"MidiNote(channel={self.channel}, pitches={self.pitches}, "
            f"velocity={self.velocity}, "
            f"program={self.program}, cc_values={self.cc_values})"
        )


def render_leaf_on(leaf: LeafOn, channel: int, time: Ratio) -> MidiNoteOn:
    velocity, articulation, transposition, timbre, panning = resolve_data(leaf, float(time))

    return MidiNoteOn(
        channel = channel,
        pitches = [p + transposition for p in leaf.pitches],
        velocity = velocity,
        program = timbre,
        cc_values= {CC_PANNING: panning},
    )


class MidiNoteOff:
    def __init__(self, channel, pitches):
        self.channel = channel              # int (0–15)
        self.pitches = pitches              # list[int]

    def __repr__(self):
        return f"MidiNote(channel={self.channel}, pitches={self.pitches}"


def render_leaf_off(leaf: LeafOff, channel: int, time: Ratio) -> MidiNoteOff:
    transposition = leaf.transposition if leaf.transposition is not None \
        else leaf.parent.value("transposition", float(time))

    return MidiNoteOff(
        channel = channel,
        pitches = [p + transposition for p in leaf.pitches])

