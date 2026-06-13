from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from fractions import Fraction
from typing import List, Tuple, Dict, Optional

import numpy as np

from experimental.core.timbre import KlangNote


class KlangPart(ABC):
    """Abstract part with sound processing"""

    @property
    @abstractmethod
    def duration(self) -> Fraction: ...

    @abstractmethod
    def render_audio(self, sample_rate: int = 44100) -> np.ndarray:
        """Render to audio samples (for synthesis)"""
        pass

    @abstractmethod
    def get_midi_with_controls(self) -> List[Tuple[Fraction, int, int, int, Dict]]:
        """Return MIDI events with continuous control data"""
        pass


@dataclass
class KlangNoteLeaf(KlangPart):
    """Note with klang processing"""
    note: KlangNote

    @property
    def duration(self) -> Fraction:
        return self.note.duration

    def render_audio(self, sample_rate: int = 44100) -> np.ndarray:
        """Synthesize this note with timbre"""
        dur_sec = float(self.note.duration) * 60 / 120  # Assuming 120 BPM
        samples = int(dur_sec * sample_rate)
        t = np.linspace(0, dur_sec, samples)

        # Base frequency
        freq = 440 * 2 ** ((self.note.pitch[0] if isinstance(self.note.pitch, list)
                            else self.note.pitch - 69) / 12)

        # Generate waveform based on timbre
        if self.note.timbre.waveform == 'sine':
            audio = np.sin(2 * np.pi * freq * t)
        elif self.note.timbre.waveform == 'saw':
            audio = 2 * (t * freq % 1) - 1
        elif self.note.timbre.waveform == 'square':
            audio = np.sign(np.sin(2 * np.pi * freq * t))

        # Apply ADSR envelope
        attack_samples = int(self.note.timbre.attack * sample_rate)
        decay_samples = int(self.note.timbre.decay * sample_rate)

        envelope = np.ones(samples)
        # Attack
        envelope[:attack_samples] = np.linspace(0, 1, attack_samples)
        # Decay to sustain
        if decay_samples > 0:
            envelope[attack_samples:attack_samples + decay_samples] = np.linspace(
                1, self.note.timbre.sustain, decay_samples)
        # Sustain (remainder)
        # Release is handled by caller or separate

        audio *= envelope * (self.note.velocity / 127)

        # Add vibrato
        if self.note.timbre.modulation['vibrato_depth'] > 0:
            vibrato = self.note.timbre.modulation['vibrato_depth'] * \
                      np.sin(2 * np.pi * self.note.timbre.modulation['vibrato_rate'] * t)
            audio = np.sin(2 * np.pi * freq * (1 + vibrato) * t)[:samples]

        return audio

    def get_midi_with_controls(self) -> List[Tuple[Fraction, int, int, int, Dict]]:
        """MIDI with continuous controllers for expression"""
        events = []

        # Note on
        events.append((
            Fraction(0), 0x90,
            self.note.pitch[0] if isinstance(self.note.pitch, list) else self.note.pitch,
            self.note.velocity,
            {'timbre': self.note.timbre}
        ))

        # Pitch bend for microtonal/melodic shape
        for time, bend in self.note.pitch_bend:
            bend_value = int(8192 + bend * 4096)  # Convert semitones to MIDI bend
            events.append((time, 0xE0, 0, bend_value & 0x7F, (bend_value >> 7) & 0x7F))

        # CC for timbre changes
        for time, amp in self.note.dynamics:
            events.append((time, 0xB0, 11, int(amp * 127)))  # Expression CC

        # Note off
        events.append((self.note.duration, 0x80,
                       self.note.pitch[0] if isinstance(self.note.pitch, list) else self.note.pitch,
                       0, {}))

        return sorted(events, key=lambda e: e[0])


@dataclass
class KlangSequence(KlangPart):
    """Sequence with continuous evolution"""
    parts: List[KlangPart]
    crossfade: float = 0.0  # Crossfade between parts in seconds
    morph_function: Optional[callable] = None  # Timbre morphing between parts

    @property
    def duration(self) -> Fraction:
        return sum(p.duration for p in self.parts)

    def render_audio(self, sample_rate: int = 44100) -> np.ndarray:
        """Render with optional crossfading"""
        if not self.parts:
            return np.array([])

        # Render each part
        rendered = [p.render_audio(sample_rate) for p in self.parts]

        # Concatenate with crossfade
        if self.crossfade > 0 and len(rendered) > 1:
            fade_samples = int(self.crossfade * sample_rate)
            result = []

            for i, audio in enumerate(rendered):
                if i == 0:
                    result.append(audio)
                else:
                    # Crossfade with previous
                    prev = result[-1]
                    fade_out = np.linspace(1, 0, fade_samples)
                    fade_in = np.linspace(0, 1, fade_samples)

                    prev[-fade_samples:] *= fade_out
                    audio[:fade_samples] *= fade_in

                    result[-1] = np.concatenate([prev, audio[fade_samples:]])

            return np.concatenate(result)
        else:
            return np.concatenate(rendered)


@dataclass
class KlangPolyphonic(KlangPart):
    """Polyphonic with spatial mixing"""
    parts: Dict[str, KlangPart]
    spatial_positions: Dict[str, Tuple[float, float, float]] = field(default_factory=dict)

    @property
    def duration(self) -> Fraction:
        return max((p.duration for p in self.parts.values()), default=Fraction(0))

    def render_audio(self, sample_rate: int = 44100) -> np.ndarray:
        """Mix parts with spatial positioning"""
        if not self.parts:
            return np.array([])

        # Render all parts
        rendered = []
        max_len = 0

        for name, part in self.parts.items():
            audio = part.render_audio(sample_rate)
            rendered.append(audio)
            max_len = max(max_len, len(audio))

        # Create stereo mix with spatial positioning
        stereo = np.zeros((max_len, 2))

        for name, audio in zip(self.parts.keys(), rendered):
            pos = self.spatial_positions.get(name, (0, 0, 0))

            # Simple stereo panning based on x position
            pan = (pos[0] + 1) / 2  # -1 to 1 -> 0 to 1
            left_gain = np.sqrt(1 - pan)
            right_gain = np.sqrt(pan)

            # Pad to max length
            padded = np.pad(audio, (0, max_len - len(audio)))

            stereo[:, 0] += padded * left_gain
            stereo[:, 1] += padded * right_gain

        return stereo
