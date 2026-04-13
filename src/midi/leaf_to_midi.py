# leaf_to_midi.py

from typing import Any

from core.domain.leafs import Leaf, LeafOn, LeafOff, DrumLeaf
from rejected.meta import Meta
from midi.midi_data import MidiNote, MidiDrumNote, MidiNoteOn, MidiNoteOff
from tools.ratio import Ratio

CC_PANNING = 10


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
        pitches = tuple(p + transposition for p in leaf.pitches),
        velocity = velocity,
        program = timbre,
        tied = leaf.tied,
        cc_values = {CC_PANNING: panning})

def render_drum(leaf: DrumLeaf, meta: Meta, time: Ratio):
    time = float(time)
    tempo = meta.value("tempo", time)
    velocity = round(1.27 * (meta.value("volume", time) + leaf.dynamic))
    velocity = max(0, min(127, velocity))
    return MidiDrumNote(
        timbre=leaf.timbre,
        duration=tempo.duration_in_seconds(leaf.duration),
        velocity=velocity)

def render_leaf_on(leaf: LeafOn, meta: Meta, time: Ratio, channel: int) -> MidiNoteOn:
    time = float(time)
    panning, timbre, transposition, velocity = resolve(leaf, meta, time)
    return MidiNoteOn(
        channel=channel,
        pitches=tuple(p + transposition for p in leaf.pitches),
        velocity=velocity,
        program=timbre,
        cc_values=[(CC_PANNING, panning)])

def render_leaf_off(leaf: LeafOff, meta: Meta, time: Ratio, channel: int) -> MidiNoteOff:
    transposition = meta.value("transposition", float(time))
    return MidiNoteOff(
        channel = channel,
        pitches = tuple(p + transposition for p in leaf.pitches))

