# leaf_to_midi.py
from typing import Optional, Any

from core.domain.leafs import Leaf, LeafOn, LeafOff
from core.domain.part_meta_score import Meta
from tools.ratio import Ratio

CC_PANNING = 10

def resolve_data(context: Meta, leaf: Leaf|LeafOn, time: Ratio) -> tuple[
    int | Any, float | Any, int | Any, float]:
    volume = leaf.volume if leaf.volume is not None else context.resolve("volume", time)
    dynamic = leaf.dynamic if leaf.dynamic is not None else context.resolve("dynamic", time)
    articulation = leaf.articulation if leaf.articulation is not None else context.resolve("articulation", time)
    program = leaf.timbre if leaf.timbre is not None else context.resolve("timbre", time)
    panning = leaf.panning if leaf.panning is not None else context.resolve("panning", time)

    # Calculate velocity
    velocity = round(1.27 * (volume + dynamic))
    # Clamp velocity to MIDI range
    velocity = max(0, min(127, velocity))

    # Calculate panning CC value
    panning = round(((panning + 1.0) / 2.0) * 127)
    panning = max(0, min(127, panning))

    return velocity, articulation, program, panning


class MidiNote:
    def __init__(self, channel, duration, pitches, velocity, articulation, program, cc_values=None):
        self.channel = channel  # int (0–15)
        self.duration = duration  # float or ticks
        self.pitches = pitches  # list[int]
        self.velocity = velocity  # int (0–127)
        self.articulation = articulation  # float
        self.program = program  # int (0–127)
        self.cc_values = cc_values or {}  # dict[int, float or int]

    def __repr__(self):
        return (
            f"MidiNote(channel={self.channel}, duration={self.duration}, pitches={self.pitches}, "
            f"velocity={self.velocity}, articulation={self.articulation}, "
            f"program={self.program}, cc_values={self.cc_values})"
        )


def render_leaf(leaf: Leaf, channel: int, time: Ratio, context: Meta) -> MidiNote:
    velocity, articulation, program, panning = resolve_data(context, leaf, time)

    midi_note = MidiNote(
        channel=channel,
        duration=leaf.duration,
        pitches=leaf.pitches,
        velocity=velocity,
        articulation=articulation,
        program=program,
        cc_values={CC_PANNING: panning},
    )

    return midi_note


class MidiNoteOn:
    def __init__(self, channel, pitches, velocity, articulation, program, cc_values=None):
        self.channel = channel              # int (0–15)
        self.pitches = pitches              # list[int]
        self.velocity = velocity            # int (0–127)
        self.articulation = articulation    # float
        self.program = program              # int (0–127)
        self.cc_values = cc_values or {}    # dict[int, float or int]

    def __repr__(self):
        return (
            f"MidiNote(channel={self.channel}, pitches={self.pitches}, "
            f"velocity={self.velocity}, articulation={self.articulation}, "
            f"program={self.program}, cc_values={self.cc_values})"
        )


def render_leaf_on(leaf: LeafOn, channel: int, time: Ratio, context: Optional[Meta] = None) -> MidiNoteOn:
    velocity, articulation, program, panning = resolve_data(context, leaf, time)

    midi_note_on = MidiNoteOn(
        channel=channel,
        pitches=leaf.pitches,
        velocity=velocity,
        articulation=articulation,
        program=program,
        cc_values={10: panning},
    )

    return midi_note_on


class MidiNoteOff:
    def __init__(self, channel, pitches):
        self.channel = channel              # int (0–15)
        self.pitches = pitches              # list[int]

    def __repr__(self):
        return f"MidiNote(channel={self.channel}, pitches={self.pitches}"


def render_leaf_off(leaf: LeafOff, channel: int, time: Ratio, context: Optional[Meta] = None) -> MidiNoteOff:
    return MidiNoteOff(channel=channel, pitches=leaf.pitches)

