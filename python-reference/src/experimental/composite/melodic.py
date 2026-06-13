from dataclasses import dataclass, field
from fractions import Fraction
from typing import List, Tuple, Optional

import numpy as np

from experimental.composite.klang import KlangPart
from experimental.core.timbre import KlangNote

@dataclass
class MelodicContour:
    """Abstract melodic shape independent of specific pitches"""
    shape: str  # 'ascending', 'descending', 'arch', 'valley', 'wave'
    interval_scale: float = 1.0  # Stretch/compress intervals
    ornamentation: List[str] = field(default_factory=list)  # 'trill', 'turn', 'mordent'

    def apply_to(self, base_notes: List[KlangNote]) -> List[KlangNote]:
        """Apply contour to a sequence of notes"""
        result = []

        for i, note in enumerate(base_notes):
            # Calculate melodic position (0-1)
            t = i / max(1, len(base_notes) - 1)

            # Generate pitch bend contour
            if self.shape == 'ascending':
                bend = t * self.interval_scale
            elif self.shape == 'descending':
                bend = (1 - t) * self.interval_scale
            elif self.shape == 'arch':
                bend = 4 * t * (1 - t) * self.interval_scale * 2
            elif self.shape == 'wave':
                bend = np.sin(t * 2 * np.pi) * self.interval_scale

            # Add ornamentation
            if 'trill' in self.ornamentation and i % 2 == 0:
                note.pitch_bend.append((note.duration / 2, 0.5))
                note.pitch_bend.append((note.duration, -0.5))

            # Create new note with applied contour
            new_note = KlangNote(
                pitch=note.pitch,
                duration=note.duration,
                timbre=note.timbre,
                pitch_bend=[(note.duration * t, bend) for t in np.linspace(0, 1, 10)]
            )
            result.append(new_note)

        return result


@dataclass
class FarbeTransform:
    """Timbre transformation over time"""
    brightness_curve: List[Tuple[Fraction, float]] = field(default_factory=list)
    texture_change: str = 'none'  # 'smooth', 'abrupt', 'granular'
    spectral_morph: Optional['SpectralNote'] = None

    def apply_to(self, part: KlangPart) -> KlangPart:
        """Apply timbre transformation to a part"""
        # Implementation would traverse the part and modify timbres
        pass
