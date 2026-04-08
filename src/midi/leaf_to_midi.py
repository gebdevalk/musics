# leaf_to_midi.py

from typing import Any

from core.domain.leafs import Leaf, LeafOn, LeafOff
from core.domain.meta import Meta
from tools.ratio import Ratio

CC_PANNING = 10

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

class MidiNoteOff:
    def __init__(self, channel, pitches):
        self.channel = channel              # int (0–15)
        self.pitches = pitches              # list[int]

    def __repr__(self):
        return f"MidiNote(channel={self.channel}, pitches={self.pitches}"

def resolve(leaf: Leaf|LeafOn, meta: Meta, time: float) \
        -> tuple[Any | None, int, int | None | Any, int]:
    transposition = meta.value("transposition", time)
    velocity = round(1.27 * (meta.value("volume", time) + leaf.dynamic))
    velocity = max(0, min(127, velocity))
    timbre = leaf.timbre or meta.value("timbre", time)
    panning = round(((meta.value("panning", time) + 1.0) / 2.0) * 127)
    panning = max(0, min(127, panning))
    return panning, timbre, transposition, velocity


def render_leaf(leaf: Leaf, meta: Meta, time: Ratio, channel: int) -> MidiNote:
    time = float(time)
    tempo = meta.value("tempo", time)
    articulation = leaf.articulation or meta.value("articulation", time)
    panning, timbre, transposition, velocity = resolve(leaf, meta, time)
    return MidiNote(
        channel = channel,
        duration = tempo.duration_in_seconds(leaf.duration),
        delay = tempo.duration_in_seconds(leaf.duration * articulation),
        pitches = (p + transposition for p in leaf.pitches),
        velocity = velocity,
        program = timbre,
        tied = leaf.tied,
        cc_values = {CC_PANNING: panning})


def render_leaf_on(leaf: LeafOn, meta: Meta, time: Ratio, channel: int) -> MidiNoteOn:
    time = float(time)
    panning, timbre, transposition, velocity = resolve(leaf, meta, time)
    return MidiNoteOn(
        channel=channel,
        pitches=(p + transposition for p in leaf.pitches),
        velocity=velocity,
        program=timbre,
        cc_values={CC_PANNING: panning})



def render_leaf_off(leaf: LeafOff, meta: Meta, time: Ratio, channel: int) -> MidiNoteOff:
    transposition = meta.value("transposition", float(time))
    return MidiNoteOff(
        channel = channel,
        pitches = (p + transposition for p in leaf.pitches))

