# globals.py
from core.domain.context import wrap_in_envelopes
from core.elements.key_scale_keyscale import KeyScale, KEYS, SCALES
from core.elements.meter import M44
from core.elements.tempo import Tempo
from midi.constants import Volume
from tools.ratio import Ratio

ROOT = wrap_in_envelopes({
    "tempo": Tempo(Ratio(1, 4), 92),
    "keyScale": KeyScale(KEYS["C"], SCALES["major"]),
    "measure": M44,
    "volume": Volume.DYNAMICS["MF"],
    "articulation": 0.9,
    "transposition": 0,
    "timbre": 0,
    "panning": 0.0,
})