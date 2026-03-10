

# ==============================================================================
# class meta
# ==============================================================================

class Meta(dict):
    """A subclass of dict that supports parent-based key lookup."""

    _win_count = 0  # unique ID counter for multiple windows

    def __init__(self, *args, parent=None, **kwargs):
        super().__init__(*args, **kwargs)
        if parent is not None and not isinstance(parent, Meta):
            raise TypeError(f"parent must be a meta instance, not {type(parent).__name__}")
        self._parent = parent

    # ── Core lookup ───────────────────────────────────────────────────────────

    def __getitem__(self, key):
        if super().__contains__(key):
            return super().__getitem__(key)
        if self._parent is not None:
            return self._parent[key]
        raise KeyError(key)

    def __contains__(self, key):
        if super().__contains__(key):
            return True
        if self._parent is not None:
            return key in self._parent
        return False

    def get(self, key, default=None):
        try:
            return self[key]
        except KeyError:
            return default

    # ── Parent management ─────────────────────────────────────────────────────

    @property
    def parent(self):
        return self._parent

    @parent.setter
    def parent(self, new_parent):
        if new_parent is not None and not isinstance(new_parent, Meta):
            raise TypeError(f"parent must be a meta instance, not {type(new_parent).__name__}")
        self._parent = new_parent

    def depth(self):
        return 0 if self._parent is None else 1 + self._parent.depth()

    def all_keys(self):
        keys = set(self.keys())
        if self._parent is not None:
            keys |= self._parent.all_keys()
        return keys

    def resolved(self):
        base = self._parent.resolved() if self._parent is not None else {}
        base.update(self)
        return base

    def __repr__(self):
        parent_info = f", parent={type(self._parent).__name__}" if self._parent is not None else ""
        return f"{type(self).__name__}({super().__repr__()}{parent_info})"


# ==============================================================================
# Musical hierarchy instances
# ==============================================================================

global_meta = Meta({
    "title":       [],
    "composer":    [],
    "genre":       [],
    "style":       [],
    "form":        [],
    "instruments": [],
    "tracks":      [],
})

track_meta = Meta({
    "instrument":    [],
    "track":         [],
    "clef":          [],
    "transposition": [],
    "staff":         [],
    "effects":       [],
}, parent=global_meta)

section_meta = Meta({
    "tempo":          [],
    "key":            [],
    "mode":           [],
    "scale":          [],
    "chord":          [],
    "phrase_mark":    [],
    "rehearsal_mark": [],
    "crescendo":      [],
    "decrescendo":    [],
}, parent=track_meta)

measure_meta = Meta({
    "measure":           [],
    "time_signature":    [],
    "meter_numerator":   [],
    "meter_denominator": [],
}, parent=section_meta)

beat_meta = Meta({
    "beat":        [],
    "subdivision": [],
    "tuplet":      [],
}, parent=measure_meta)

note_meta = Meta({
    "pitch":        [],
    "octave":       [],
    "duration":     [],
    "volume":       [],
    "dynamic":      [],
    "panning":      [],
    "articulation": [],
    "accent":       [],
    "onset":        [],
    "offset":       [],
    "attack":       [],
    "release":      [],
    "vibrato":      [],
    "bend":         [],
    "ornaments":    [],
    "tie":          [],
    "slur":         [],
    "tuning":       [],
    "channel":      [],
}, parent=beat_meta)

# ── Parameter config: name → (min, max, default) ──────────────────
PARAM_CONFIG = {
    # ── Note ──────────────────────────────────────────────────────
    "pitch": (0, 127, 60),
    "octave": (0, 8, 4),
    "duration": (0, 200, 100),
    "volume": (0, 100, 50),
    "dynamic": (0, 6, 3),
    "panning": (-1.0, 0.0, 1.0),
    "articulation": (0.0, 0.9, 4.0),
    "accent": (0, 1, 0),
    # "onset":              (0,    960,  0),
    # "offset":             (0,    960,  480),
    # "attack":             (0,    127,  10),
    # "release":            (0,    127,  10),
    "vibrato": (0, 127, 0),
    "bend": (-100, 100, 0),
    "ornaments": (0, 4, 0),
    "tie": (0, 1, 0),
    # "slur":               (0,    1,    0),
    # "tuning":             (-100, 100,  0),
    "channel": (0, 15, 0),
    # ── Beat ──────────────────────────────────────────────────────
    # "beat":               (1,    16,   1),
    # "subdivision":        (1,    8,    1),
    # "tuplet":             (1,    9,    1),
    # ── Measure ───────────────────────────────────────────────────
    "measure": (1, 999, 1),
    "time_signature": (0, 10, 0),
    "meter_numerator": (1, 16, 4),
    "meter_denominator": (1, 16, 4),
    # ── Section ───────────────────────────────────────────────────
    "tempo": (20, 300, 120),
    "key": (0, 11, 0),
    "mode": (0, 6, 0),
    "scale": (0, 10, 0),
    "chord": (0, 20, 0),
    # "phrase_mark":        (0,    1,    0),
    # "rehearsal_mark":     (0,    20,   0),
    # "crescendo":          (0,    1,    0),
    # "decrescendo":        (0,    1,    0),
    # ── Track ─────────────────────────────────────────────────────
    "instrument": (0, 127, 0),
    # "track":              (0,    15,   0),
    # "clef":               (0,    4,    0),
    "transposition": (-12, 12, 0),
    # "staff":              (1,    4,    1),
    "effects": (0, 10, 0),
    # ── Global ────────────────────────────────────────────────────
    # "genre":              (0,    10,   0),
    # "style":              (0,    10,   0),
    # "form":               (0,    10,   0),
}