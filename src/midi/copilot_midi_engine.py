import asyncio
import time
from collections import defaultdict
from dataclasses import dataclass
from typing import List

import mido

from core.domain.composite import Composite, Concurrent
from core.domain.leafs import Leaf, LeafOn, LeafOff, Algorithm
from core.domain.meta import Part, Meta
from core.domain.score import SCORE
from midi.leaf_to_midi import (
    render_leaf, render_leaf_on, render_leaf_off,
    MidiNote, MidiNoteOn, MidiNoteOff,
)
from tools.ratio import Ratio

@dataclass
class Channel:
    number: int
    offset: Ratio
    sounding_note_counts: dict[int, int]

    # --------------------------------------------------------
    # Sounding notes registration
    # --------------------------------------------------------

    def register(self, pitch: int):
        if pitch > 0:
            self.sounding_note_counts[pitch] += 1

    def unregister(self, pitch: int):
        count = self.sounding_note_counts.get(pitch, 0)
        if count > 1:
            self.sounding_note_counts[pitch] -= 1
        elif count == 1:
            del self.sounding_note_counts[pitch]
        # count == 0: already unregistered, do nothing (or raise)

    def sounding_count(self, pitch: int):
        return self.sounding_note_counts.get(pitch, 0)


class MidiEngineAsync:
    """
    Fully non-blocking asyncio-based MIDI engine.

    - Leaf / LeafOn / LeafOff: scheduled immediately
    - Composite: sequential container, iterated with `for child in part`
    - Concurrent: parallel container, children started at same offset
    - Algorithm: expanded via _generate()
    - No global timeline; events are scheduled as they are rendered.
    """
    def __init__(self):
        """
        midi_out: synchronous function that sends a MIDI event
                  (e.g. to a MIDI port or file writer).
        """
        self.midi_out = None
        # self.tasks: List[asyncio.Task] = []
        # self.offsets: dict[int, Ratio] = {}
        # self.sounding_note_count: dict[int, dict[int, int]] = (
        #     defaultdict(lambda: defaultdict(int)))
        self.channel_pool: asyncio.Queue = asyncio.Queue()

    # --------------------------------------------------------
    # MIDI port management
    # --------------------------------------------------------

    def open_port(self):
        self.midi_out = mido.open_output()
        for number in [i for i in range(15, -1, -1) if i != 9]:
            self.channel_pool.put_nowait(Channel(number, Ratio(0), defaultdict(int)))
        # for ch in [i for i in range(0, 16)]:
        #     self.offsets[ch]= Ratio(0)
        return None

    def close_port(self):
        if self.midi_out is not None:
            self.midi_out.close()

    # --------------------------------------------------------
    # Public API
    # --------------------------------------------------------

    async def play(self, part: Part):
        """
        Walks the Part tree and schedules events immediately.
        Returns when all scheduled events have been played.
        """
        await self.perform(await self.channel_pool.get(), part, SCORE)
        # if self.tasks:
        #     await asyncio.gather(*self.tasks)

    # --------------------------------------------------------
    # Tree walk
    # --------------------------------------------------------

    async def perform(self, channel: Channel, part: Part, meta: Meta):
        """
        Dispatch based on Part type:
        - Leaf / LeafOn / LeafOff: render + schedule
        - Composite- > sequential (iteration)
        - Concurrent ->  parallel (iteration)
        - Algorithm -> expand via _generate()
        """

        # -----------------------------
        # Leaf
        # -----------------------------
        if isinstance(part, Leaf):
            note = render_leaf(part, meta, channel.offset, channel.number)
            channel.offset += note.duration
            await self.play_note(channel, note)
            return

        # -----------------------------
        # Meta events
        # -----------------------------

        if isinstance(part, LeafOn):
            note_on = render_leaf_on(part, meta, channel.offset, channel.number)
            self.play_note_on(channel, note_on)
            return

        if isinstance(part, LeafOff):
            note_off = render_leaf_off(part, meta, channel.offset, channel.number)
            self.play_note_off(channel, note_off)
            return

        # -----------------------------
        # Composite (sequential)
        # -----------------------------
        if isinstance(part, Composite):
            # start with a new channel
            self.channel_pool.put_nowait(channel)
            channel = self.channel_pool.get_nowait()
            try:
                for child in part:  # iteration over children
                    await self.perform(channel, child, part)
            finally:
                self.channel_pool.put_nowait(channel)  # always returned, even on error
            return

        # -----------------------------
        # Concurrent (parallel)
        # -----------------------------

        if isinstance(part, Concurrent):
            subtasks: List[asyncio.Task] = []
            for child in part:  # iteration over children
                # all children start at the same offset
                subtasks.append(
                    asyncio.create_task(self.perform(channel, child, SCORE))
                )
            if subtasks:
                await asyncio.gather(*subtasks)
            return

        # -----------------------------
        # Algorithm
        # -----------------------------
        if isinstance(part, Algorithm):
            generated = part.generate()
            for child in generated:
                await self.perform(channel, child, meta)
            return

        raise TypeError(f"Unknown Part type: {type(part)}")

    # --------------------------------------------------------
    # Async scheduling
    # --------------------------------------------------------

    async def play_note(self, channel: Channel, note: MidiNote):
        for pitch in note.pitches:
            self.note_on(channel, pitch, note.velocity)
        if not note.tied:
            asyncio.create_task(self.note_off_after_delay(channel, note))
        await asyncio.sleep(note.duration)

    async def note_off_after_delay(self, channel: Channel, note: MidiNote):
        # note.delay can be longer than note.duration
        await asyncio.sleep(note.delay)
        for pitch in note.pitches:
            self.note_off(channel, pitch)

    def play_note_on(self, channel: Channel, note: MidiNoteOn):
        for pitch in note.pitches:
            self.note_on(channel, pitch, note.velocity)

    def play_note_off(self, channel: Channel, note: MidiNoteOff):
        for pitch in note.pitches:
            self.note_off(channel, pitch)

    def note_on(self, channel: Channel, pitch: int, velocity: int):
        channel.register(pitch)
        self.midi_out.send(mido.Message(
            'note_on', note=pitch, velocity=velocity, channel=channel.number))

    def note_off(self, channel: Channel, pitch: int):
        if channel.sounding_count(pitch) == 1:
            self.midi_out.send(mido.Message(
                'note_off', note=pitch, velocity=0, channel=channel.number))
        channel.unregister(pitch)
