import asyncio
from collections import defaultdict
from dataclasses import dataclass, field
from typing import List, Set, Tuple, Optional

import mido

from core.domain.composite import Composite, Concurrent
from core.domain.leafs import Leaf, LeafOn, LeafOff, Algorithm, DrumLeaf
from core.domain.meta import Part, Meta
from core.domain.score import SCORE
from midi.leaf_to_midi import (
    render_leaf, render_leaf_on, render_leaf_off,
    MidiNote, MidiNoteOn, MidiNoteOff, render_drum, MidiDrumNote,
)
from tools.ratio import Ratio

DRUM_CHANNEL = 9

# --------------------------------------------------------
# Helpers
# --------------------------------------------------------

async def sleep_until(target_time: float):
    loop = asyncio.get_running_loop()
    delay = target_time - loop.time()
    if delay > 0:
        await asyncio.sleep(delay)


# --------------------------------------------------------
# Channel
# --------------------------------------------------------

@dataclass
class Channel:
    number: int
    offset: Ratio
    program: Optional[int] = None
    sounding_notes: Set[Tuple[int, ...]] = field(default_factory=set)

    def register(self, pitches: tuple[int]):
        self.sounding_notes.add(pitches)

    def sounding(self, pitches: tuple[int]) -> bool:
        return pitches in self.sounding_notes

    def unregister(self, pitches: tuple[int]):
        if self.sounding(pitches):
            self.sounding_notes.remove(pitches)


# --------------------------------------------------------
# Engine
# --------------------------------------------------------

class MidiEngineAsync:

    def __init__(self):
        self.midi_out = None
        self.channel_pool: asyncio.Queue[Channel] = asyncio.Queue()
        self.tasks: List[asyncio.Task] = []

    # --------------------------------------------------------
    # MIDI
    # --------------------------------------------------------

    def open_port(self):
        self.midi_out = mido.open_output()
        for number in [i for i in range(15, -1, -1) if i != 9]:
            self.channel_pool.put_nowait(
                Channel(number, Ratio(0), set())
            )

    def close_port(self):
        if self.midi_out:
            self.midi_out.close()

    # --------------------------------------------------------
    # Channel pool
    # --------------------------------------------------------

    async def acquire_channel(self) -> Channel:
        ch = await self.channel_pool.get()
        return ch

    def release_channel(self, ch: Channel):
        # reset state before reuse
        ch.offset = Ratio(0)
        ch.sounding_note_counts = defaultdict(int)
        self.channel_pool.put_nowait(ch)

    # --------------------------------------------------------
    # Public API
    # --------------------------------------------------------

    async def play(self, part: Part):
        start_time = asyncio.get_running_loop().time()
        root_channel = await self.acquire_channel()
        try:
            await self.perform(root_channel, part, SCORE, start_time)
            if self.tasks:
                await asyncio.gather(*self.tasks)
        finally:
            self.release_channel(root_channel)

    # --------------------------------------------------------
    # Tree walk
    # --------------------------------------------------------

    async def perform(self, channel: Channel,
                      part: Part, meta: Meta, start_time: float):

        # -----------------------------
        # Leaf
        # -----------------------------
        if isinstance(part, Leaf):
            note = render_leaf(part, meta, channel.offset, channel.number)
            target_time = start_time + float(channel.offset)
            await sleep_until(target_time)
            await self.play_note(channel, note, start_time)
            channel.offset += note.duration
            return

        # -----------------------------
        # DrumLeaf
        # -----------------------------
        if isinstance(part, DrumLeaf):
            drum_note = render_drum(part, meta, channel.offset)
            target_time = start_time + float(channel.offset)
            await sleep_until(target_time)
            await self.play_drum_note(channel, drum_note, start_time)
            channel.offset += part.duration
            return

        # -----------------------------
        # Meta events
        # -----------------------------
        if isinstance(part, LeafOn):
            note_on = render_leaf_on(
                part, meta, channel.offset, channel.number)
            target_time = start_time + float(channel.offset)
            await sleep_until(target_time)
            self.play_note_on(channel, note_on)
            return

        if isinstance(part, LeafOff):
            note_off = render_leaf_off(
                part, meta, channel.offset, channel.number)
            target_time = start_time + float(channel.offset)
            await sleep_until(target_time)
            self.play_note_off(channel, note_off)
            return

        # -----------------------------
        # Composite (sequential)
        # -----------------------------
        if isinstance(part, Composite):
            for child in part:
                await self.perform(channel, child, part, start_time)
            return

        # -----------------------------
        # Concurrent (parallel)
        # -----------------------------
        if isinstance(part, Concurrent):
            subtasks: List[asyncio.Task] = []
            for child in part:
                ch = await self.acquire_channel()
                # inherit timing, isolate state
                ch.offset = channel.offset
                ch.sounding_note_counts = defaultdict(int)
                async def run(ch=ch, child=child):
                    try:
                        await self.perform(ch, child, meta, start_time)
                    finally:
                        self.release_channel(ch)
                subtasks.append(asyncio.create_task(run()))
            if subtasks:
                await asyncio.gather(*subtasks)
            return


        # -----------------------------
        # Algorithm
        # -----------------------------
        if isinstance(part, Algorithm):
            generated = part.generate()
            for child in generated:
                await self.perform(channel, child, meta, start_time)
            return

        raise TypeError(f"Unknown Part type: {type(part)}")


    # --------------------------------------------------------
    # Playback
    # --------------------------------------------------------

    async def play_note(
            self, channel: Channel, note: MidiNote, start_time: float):
        if note.program != channel.program:
            self.program_change(channel, note.program)
        if note.cc_values:
            for cc in note.cc_values:
                self.control_change(channel, cc[0], cc[1])
        if not channel.sounding(note.pitches) :
            if not note.tied: # normal note
                self.play_note_on(channel, note)
                await self.create_note_off_task(channel, note, start_time)
            else:  # first tied note
                channel.register(note.pitches)
                self.play_note_on(channel, note)
        else: # note is sounding
            if not note.tied: # note was tied to previous note
                channel.unregister(note.pitches)
                await self.create_note_off_task(channel, note, start_time)
            else: # tied to previous and next note, nothing to do
                pass

    async def play_drum_note(self, channel: Channel, note: MidiDrumNote, start_time: float):
        self.midi_out.send(mido.Message(
            'note_on', channel=DRUM_CHANNEL, note=note.timbre, velocity=note.velocity))
        await asyncio.sleep(note.duration)
        self.midi_out.send(mido.Message(
            'note_off', channel=DRUM_CHANNEL, note=note.timbre, velocity=0))


    async def create_note_off_task(self, channel: Channel, note: MidiNote, start_time: float):
        self.tasks.append(
            asyncio.create_task(
                self.note_off_after_delay(channel, note, start_time)
            )
        )

    async def note_off_after_delay(
            self, channel: Channel, note: MidiNote, start_time: float):
        target_time = start_time + float(channel.offset) + float(note.delay)
        await sleep_until(target_time)
        for pitch in note.pitches:
            self.note_off(channel, pitch)

    def play_note_on(self, channel: Channel, note: MidiNote|MidiNoteOn):
        for pitch in note.pitches:
            self.note_on(channel, pitch, note.velocity)

    def play_note_off(self, channel: Channel, note: MidiNoteOff):
        for pitch in note.pitches:
            self.note_off(channel, pitch)

    # --------------------------------------------------------
    # MIDI primitives
    # --------------------------------------------------------

    def note_on(self, channel: Channel, pitch: int, velocity: int):
        self.midi_out.send(
            mido.Message(
                'note_on', note=pitch, velocity=velocity, channel=channel.number)
        )

    def note_off(self, channel: Channel, pitch: int):
        self.midi_out.send(
            mido.Message(
                'note_off', note=pitch, velocity=0, channel=channel.number)
        )

    # CONTROL
    def control_change(self, channel: Channel, control: int, value: int):
        self.midi_out.send(mido.Message(
            "control_change", control = control, value = value, channel = channel.number
        ))

    # PROGRAM CHANGE
    def program_change(self, channel: Channel, program: int):
        channel.program = program  # int (0–127)
        self.midi_out.send(mido.Message(
            "program_change", program = program, channel = channel.number,
        ))
