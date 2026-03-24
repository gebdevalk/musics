# meta.py

from core.domain.point_envelope import Envelope


# ==============================================================================
# class meta
# ==============================================================================

class Meta(dict):
    """A subclass of dict that supports parent-based key lookup."""

    _win_count = 0  # unique ID counter for multiple windows

    def __init__(self, parent=None, *args, **kwargs):
        super().__init__(*args, **kwargs)
        if parent is not None and not isinstance(parent, Meta):
            raise TypeError(f"parent must be a meta instance, not {type(parent).__name__}")
        self._parent = parent

    # ── Core lookup ───────────────────────────────────────────────────────────

    def __getitem__(self, key):
        if super().__contains__(key):
            value = super().__getitem__(key)
            if not isinstance(value, Envelope) or len(value) > 0:
                return value
        if self._parent is not None:
            return self._parent[key]
        raise KeyError(key)

    def __contains__(self, key):
        if super().__contains__(key):
            value = super().__getitem__(key)
            if not isinstance(value, Envelope) or len(value) > 0:
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

global_meta = Meta(None, {
    "title":       [],
    "composer":    [],
    "genre":       [],
    "style":       [],
    "form":        [],
    "instruments": [],
    "tracks":      [],
})

track_meta = Meta(global_meta, {
    "instrument":    [],
    "track":         [],
    "clef":          [],
    "transposition": [],
    "staff":         [],
    "effects":       [],
})

beat_meta = Meta(track_meta,{
    "beat":        Envelope(),
    "subdivision": Envelope(),
    "tuplet":      Envelope(),
})

composite_meta = Meta(beat_meta, {
    "tempo": Envelope(),
    "keyScale": Envelope(),
    "measure": Envelope(),
    "volume": Envelope(),
    "dynamic": Envelope(),
    "articulation": Envelope(),
    "panning": Envelope(),
    # "vibrato": [],  event type
})

note_instance = Meta(beat_meta), {
    "pitch":        [],
    "octave":       [],
    "duration":     [],
    "volume":       [],
    "dynamic":      [],
    "articulation": [],
    "accent":       [],
    "ornament":     [],
    "tie":          [],
    "panning":      [],
    # "vibrato":      [],
    # "bend":         [],
    # "onset":        [],
    # "offset":       [],
    # "attack":       [],
    # "release":      [],
    # "slur":         [],
    # "tuning":       [],
    # "channel":      [],
}
