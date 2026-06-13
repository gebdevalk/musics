# midi_engine_async.py

import asyncio
from dataclasses import dataclass, field
from fractions import Fraction
from typing import List, Set, Tuple, Optional

import mido

from archive.old.domain.leaf import Leaf, LeafOn, LeafOff, Algorithm, DrumLeaf
from output.midi.midi_data import MidiNote, MidiDrumNote, MidiNoteOn, MidiNoteOff
from output.midi.leaf_to_midi import (
    render_leaf, render_leaf_on, render_leaf_off, render_drum,
)
from timing import (
    compute_onset,
    compute_noteoff_time,
    compute_drum_noteoff_time,
)

DRUM_CHANNEL = 9


async def sleep_until(target_time: float):
    loop = asyncio.get_running_loop()
    delay = target_time - loop.time()
    if delay > 0:
        await asyncio.sleep(delay)


@dataclass
class Channel:
    number: int
    offset: Fraction
    program: Optional[int] = None
    sounding_notes: Set[Tuple[int, ...]] = field(default_factory=set)

    def register(self, pitches: tuple[int, ...]):
        self.sounding_notes.add(pitches)

    def sounding(self, pitches: tuple[int, ...]) -> bool:
        return pitches in self.sounding_notes

    def unregister(self, pitches: tuple[int, ...]):
        if self.sounding(pitches):
            self.sounding_notes.remove(pitches)


class MidiEngineAsync:

    def __init__(self):
        self.midi_out = None
        self.channel_pool: asyncio.Queue[Channel] = asyncio.Queue()
        self.tasks: List[asyncio.Task] = []

    # ---------------- MIDI port ----------------

    def open_port(self):
        self.midi_out = mido.open_output()
        for number in [i for i in range(15, -1, -1) if i != DRUM_CHANNEL]:
            self.channel_pool.put_nowait(Channel(number, Fraction(0)))

    def close_port(self):
        if self.midi_out:
            self.midi_out.close()

    # ---------------- Channel pool ----------------

    async def acquire_channel(self) -> Channel:
        return await self.channel_pool.get()

    def release_channel(self, ch: Channel):
        ch.offset = Fraction(0)
        ch.sounding_notes.clear()
        self.channel_pool.put_nowait(ch)

    # ---------------- Public API ----------------

    async def play(self, part):
        start_time = asyncio.get_running_loop().time()
        root_channel = await self.acquire_channel()
        try:
            await self.perform(root_channel, part, start_time)
            if self.tasks:
                await asyncio.gather(*self.tasks)
        finally:
            self.release_channel(root_channel)

    # ---------------- Tree walk ----------------

    async def perform(self, channel: Channel, part, start_time: float):

        # Leaf
        if isinstance(part, Leaf):
            time_Fraction = channel.offset
            time_f = float(time_Fraction)
            ctx = part.context
            micro = ctx.value("micro", time_f) or 0.0  # seconds

            onset_time = compute_onset(start_time, time_Fraction, micro)
            note = render_leaf(part, time_Fraction, channel.number)

            await sleep_until(onset_time)
            await self.play_note(channel, note, onset_time)

            channel.offset += part.duFractionn
            return

        # DrumLeaf
        if isinstance(part, DrumLeaf):
            time_Fraction = channel.offset
            time_f = float(time_Fraction)
            ctx = part.context
            micro = ctx.value("micro", time_f) or 0.0

            onset_time = compute_onset(start_time, time_Fraction, micro)
            drum_note = render_drum(part, time_Fraction)

            await sleep_until(onset_time)
            await self.play_drum_note(drum_note, onset_time)
            channel.offset += part.duFractionn
            return

        # LeafOn
        if isinstance(part, LeafOn):
            time_Fraction = channel.offset
            time_f = float(time_Fraction)
            ctx = part.context
            micro = ctx.value("micro", time_f) or 0.0

            onset_time = compute_onset(start_time, time_Fraction, micro)
            note_on = render_leaf_on(part, time_Fraction, channel.number)

            await sleep_until(onset_time)
            self.play_note_on(channel, note_on)
            return

        # LeafOff
        if isinstance(part, LeafOff):
            time_Fraction = channel.offset
            time_f = float(time_Fraction)
            ctx = part.context
            micro = ctx.value("micro", time_f) or 0.0

            onset_time = compute_onset(start_time, time_Fraction, micro)
            note_off = render_leaf_off(part, time_Fraction, channel.number)

            await sleep_until(onset_time)
            self.play_note_off(channel, note_off)
            return

        # Sequential composite (if you still use Composite as “sequence”)
        from archive.composite import Composite
        if isinstance(part, Composite):
            for child in part:
                await self.perform(channel, child, start_time)
            return

        # Algorithm
        if isinstance(part, Algorithm):
            generated = part.generate()
            for child in generated:
                await self.perform(channel, child, start_time)
            return

        raise TypeError(f"Unknown Part type: {type(part)}")

    # ---------------- Playback ----------------

    async def play_note(
        self, channel: Channel, note: MidiNote, onset_time: float
    ):
        if note.program != channel.program:
            self.program_change(channel, note.program)

        if note.cc_values:
            for cc, value in note.cc_values.items():
                self.control_change(channel, cc, value)

        if not channel.sounding(note.pitches):
            if not note.tied:  # normal note
                self.play_note_on(channel, note)
                await self.create_note_off_task(channel, note, onset_time)
            else:  # first tied note
                channel.register(note.pitches)
                self.play_note_on(channel, note)
        else:
            if not note.tied:  # end of tie chain
                channel.unregister(note.pitches)
                await self.create_note_off_task(channel, note, onset_time)
            else:
                # middle of tie chain: nothing to do
                pass

    async def play_drum_note(self, note: MidiDrumNote, onset_time: float):
        self.midi_out.send(mido.Message(
            'note_on', channel=DRUM_CHANNEL, note=note.timbre, velocity=note.velocity
        ))
        # duFractionn_played already includes articulation
        noteoff_time = compute_drum_noteoff_time(onset_time, note)
        await sleep_until(noteoff_time)
        self.midi_out.send(mido.Message(
            'note_off', channel=DRUM_CHANNEL, note=note.timbre, velocity=0
        ))

    async def create_note_off_task(
        self, channel: Channel, note: MidiNote, onset_time: float
    ):
        self.tasks.append(
            asyncio.create_task(
                self.note_off_after_delay(channel, note, onset_time)
            )
        )

    async def note_off_after_delay(
        self, channel: Channel, note: MidiNote, onset_time: float
    ):
        target_time = compute_noteoff_time(onset_time, note)
        await sleep_until(target_time)
        for pitch in note.pitches:
            self.note_off(channel, pitch)

    def play_note_on(self, channel: Channel, note: MidiNote | MidiNoteOn):
        for pitch in note.pitches:
            self.note_on(channel, pitch, note.velocity)

    def play_note_off(self, channel: Channel, note: MidiNoteOff):
        for pitch in note.pitches:
            self.note_off(channel, pitch)

    # ---------------- MIDI primitives ----------------

    def note_on(self, channel: Channel, pitch: int, velocity: int):
        self.midi_out.send(
            mido.Message(
                'note_on', note=pitch, velocity=velocity, channel=channel.number
            )
        )

    def note_off(self, channel: Channel, pitch: int):
        self.midi_out.send(
            mido.Message(
                'note_off', note=pitch, velocity=0, channel=channel.number
            )
        )

    def control_change(self, channel: Channel, control: int, value: int):
        self.midi_out.send(mido.Message(
            "control_change", control=control, value=value, channel=channel.number
        ))

    def program_change(self, channel: Channel, program: int):
        channel.program = program
        self.midi_out.send(mido.Message(
            "program_change", program=program, channel=channel.number
        ))
