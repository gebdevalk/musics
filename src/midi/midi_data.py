# midi_data.py

from dataclasses import dataclass
from typing import Tuple, Dict, List


@dataclass
class MidiNote:
    channel: int
    duration_notated: float      # original musical duration
    duration_played: float       # articulation-scaled duration
    pitches: Tuple[int, ...]
    velocity: int
    program: int
    tied: bool
    cc_values: Dict[int, int]


@dataclass
class MidiDrumNote:
    timbre: int
    duration_notated: float
    duration_played: float
    velocity: int


@dataclass
class MidiNoteOn:
    channel: int
    pitches: Tuple[int, ...]
    velocity: int
    program: int
    cc_values: List[tuple]


@dataclass
class MidiNoteOff:
    channel: int
    pitches: Tuple[int, ...]
