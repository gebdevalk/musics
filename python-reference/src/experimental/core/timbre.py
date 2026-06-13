
from fractions import Fraction
from dataclasses import dataclass, field
from typing import List, Union, Dict, Optional, Tuple

@dataclass
class Timbre:
    """Sound color parameters"""
    waveform: str = 'sine' # sine, square, saw, triangle, noise
    brightness: float = 0.5 # 0-1 (filter cutoff)
    attack: float = 0.01 # seconds
    decay: float = 0.1 # seconds
    sustain: float = 0.7 # 0-1 level
    release: float = 0.2 # seconds
    modulation: Dict[str, float] = field(default_factory=lambda: {
        'vibrato_rate': 5.0, # Hz
        'vibrato_depth': 0.02, # semitones
        'tremolo_rate': 4.0, # Hz
        'tremolo_depth': 0.1 # amplitude mod
    })

@dataclass
class SpectralNote:
    """Note with spectral content (multiple partials)"""
    fundamental: int # MIDI pitch
    partials: List[Tuple[float, float]] # (harmonic_ratio, amplitude) e.g. [(1,1.0), (2,0.5), (3,0.3)]
    duration: Fraction
    timbre: Timbre = field(default_factory=Timbre)
    velocity: int = 64
    spatial: Tuple[float, float, float] = (0.0, 0.0, 0.0) # x,y,z position in sound space

@dataclass
class KlangNote:
    """Note with extended sound parameters"""
    pitch: Union[int, List[int]] # Single pitch or microtonal detune list
    duration: Fraction
    timbre: Timbre = field(default_factory=Timbre)
    dynamics: List[Tuple[Fraction, float]] = field(default_factory=list) # (time, amplitude) shape
    pitch_bend: List[Tuple[Fraction, float]] = field(default_factory=list) # (time, semitones) shape
    expression: Dict[str, float] = field(default_factory=dict) # legato, staccato, accent, etc.
