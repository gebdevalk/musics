# score.py
from typing import Optional, Dict, Any

from core.domain.meta import Meta
from core.domain.part import Part
from core.elements.key_scale_keyscale import KeyScale, KEYS, SCALES
from core.elements.meter import M44
from core.elements.tempo import Tempo
from midi.constants import Volume
from tools.ratio import Ratio


class Score(Meta):
    """Root of the Meta parent chain. Holds global musical defaults and the result of parsing."""
    part: Optional[Part] = None  # The main part of the score, if any
    def __init__(self, values: Dict[str, Any] = None):
        super().__init__(parent=None, data=None)


SCORE = Score(values={
    "tempo": Tempo(Ratio(1, 4), 92),
    "keyScale": KeyScale(KEYS["C"], SCALES["major"]),
    "measure": M44,
    "volume": Volume.DYNAMICS["MF"],
    "articulation": 0.9,
    "transposition": 0,
    "timbre": 0,
    "panning": 0.0,
})