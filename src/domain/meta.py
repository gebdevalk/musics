import dearpygui.dearpygui as dpg


# ==============================================================================
# class meta
# ==============================================================================

class meta(dict):
    """A subclass of dict that supports parent-based key lookup."""

    _win_count = 0  # unique ID counter for multiple windows

    def __init__(self, *args, parent=None, **kwargs):
        super().__init__(*args, **kwargs)
        if parent is not None and not isinstance(parent, meta):
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
        if new_parent is not None and not isinstance(new_parent, meta):
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

    # ── Static display method ─────────────────────────────────────────────────

    @staticmethod
    def display(data: dict, title: str = "meta — Parameters"):
        """
        Display a Dear PyGui window with vertical sliders for all keys in data.

        Parameters
        ----------
        data  : any dict (or meta) whose keys are parameter names
        title : window title
        """

        if not isinstance(data, dict):
            raise TypeError(f"display() expects a dict, got {type(data).__name__}")

        # ── Parameter config: name → (min, max, default) ──────────────────
        PARAM_CONFIG = {
            # ── Note ──────────────────────────────────────────────────────
            "pitch":              (0,    127,  60),
            "octave":             (0,    8,    4),
            "duration":           (0,    200,  100),
            "volume":             (0,    127,  80),
            "dynamic":            (0,    6,    3),
            "panning":            (-100, 100,  0),
            "articulation":       (0,    4,    0),
            "accent":             (0,    1,    0),
            "onset":              (0,    960,  0),
            "offset":             (0,    960,  480),
            "attack":             (0,    127,  10),
            "release":            (0,    127,  10),
            "vibrato":            (0,    127,  0),
            "bend":               (-100, 100,  0),
            "ornaments":          (0,    4,    0),
            "tie":                (0,    1,    0),
            "slur":               (0,    1,    0),
            "tuning":             (-100, 100,  0),
            "channel":            (0,    15,   0),
            # ── Beat ──────────────────────────────────────────────────────
            "beat":               (1,    16,   1),
            "subdivision":        (1,    8,    1),
            "tuplet":             (1,    9,    1),
            # ── Measure ───────────────────────────────────────────────────
            "measure":            (1,    999,  1),
            "time_signature":     (0,    10,   0),
            "meter_numerator":    (1,    16,   4),
            "meter_denominator":  (1,    16,   4),
            # ── Section ───────────────────────────────────────────────────
            "tempo":              (20,   300,  120),
            "key":                (0,    11,   0),
            "mode":               (0,    6,    0),
            "scale":              (0,    10,   0),
            "chord":              (0,    20,   0),
            "phrase_mark":        (0,    1,    0),
            "rehearsal_mark":     (0,    20,   0),
            "crescendo":          (0,    1,    0),
            "decrescendo":        (0,    1,    0),
            # ── Track ─────────────────────────────────────────────────────
            "instrument":         (0,    127,  0),
            "track":              (0,    15,   0),
            "clef":               (0,    4,    0),
            "transposition":      (-12,  12,   0),
            "staff":              (1,    4,    1),
            "effects":            (0,    10,   0),
            # ── Global ────────────────────────────────────────────────────
            "genre":              (0,    10,   0),
            "style":              (0,    10,   0),
            "form":               (0,    10,   0),
        }

        # ── Unique tags ────────────────────────────────────────────────────
        meta._win_count += 1
        uid       = meta._win_count
        win_tag   = f"meta_win_{uid}"
        hide_tag  = f"meta_hide_{uid}"
        close_tag = f"meta_close_{uid}"

        # ── Cascade offset so windows don't stack exactly ──────────────────
        offset = (uid - 1) * 30
        pos_x  = 20 + offset
        pos_y  = 20 + offset

        win_w  = max(500, len(data) * 82 + 40)
        win_h  = 460

        # ── Callbacks ──────────────────────────────────────────────────────
        def on_hide(s, a, u):
            dpg.hide_item(u)

        def on_close(s, a, u):
            dpg.delete_item(u)

        def on_slide(sender, app_data, user_data):
            dpg.set_value(user_data, str(app_data))

        # ── Window ─────────────────────────────────────────────────────────
        with dpg.window(
            label=f"  {title}",
            tag=win_tag,
            width=win_w,
            height=win_h,
            pos=[pos_x, pos_y],
            no_close=True,          # we supply our own close button
        ):

            # ── Toolbar ────────────────────────────────────────────────────
            with dpg.group(horizontal=True):
                dpg.add_button(
                    label="  Hide  ",
                    tag=hide_tag,
                    callback=on_hide,
                    user_data=win_tag,
                )
                dpg.add_spacer(width=6)
                dpg.add_button(
                    label="  Close  ",
                    tag=close_tag,
                    callback=on_close,
                    user_data=win_tag,
                )

            # ── Per-button colours ─────────────────────────────────────────
            with dpg.theme() as hide_theme:
                with dpg.theme_component(dpg.mvButton):
                    dpg.add_theme_color(dpg.mvThemeCol_Button,        (166, 227, 161, 180))
                    dpg.add_theme_color(dpg.mvThemeCol_ButtonHovered, (166, 227, 161, 255))
                    dpg.add_theme_color(dpg.mvThemeCol_ButtonActive,  (140, 200, 135, 255))
                    dpg.add_theme_color(dpg.mvThemeCol_Text,          ( 17,  17,  27, 255))
            dpg.bind_item_theme(hide_tag, hide_theme)

            with dpg.theme() as close_theme:
                with dpg.theme_component(dpg.mvButton):
                    dpg.add_theme_color(dpg.mvThemeCol_Button,        (243, 139, 168, 180))
                    dpg.add_theme_color(dpg.mvThemeCol_ButtonHovered, (243, 139, 168, 255))
                    dpg.add_theme_color(dpg.mvThemeCol_ButtonActive,  (220, 100, 130, 255))
                    dpg.add_theme_color(dpg.mvThemeCol_Text,          ( 17,  17,  27, 255))
            dpg.bind_item_theme(close_tag, close_theme)

            dpg.add_separator()
            dpg.add_spacer(height=6)

            # ── Slider cards ───────────────────────────────────────────────
            with dpg.group(horizontal=True):
                for col, (param, raw) in enumerate(data.items()):
                    lo, hi, dflt = PARAM_CONFIG.get(param, (0, 127, 0))

                    if isinstance(raw, list) and raw:
                        val = int(raw[0])
                    elif isinstance(raw, (int, float)):
                        val = int(raw)
                    else:
                        val = dflt
                    val = max(lo, min(hi, val))

                    lbl_tag   = f"val_{win_tag}_{col}"
                    slide_tag = f"sld_{win_tag}_{col}"

                    with dpg.group():
                        # live readout
                        dpg.add_text(str(val), tag=lbl_tag,
                                     color=(137, 180, 250))
                        # hi marker
                        dpg.add_text(f"^ {hi}",
                                     color=(108, 112, 134))
                        # vertical slider
                        dpg.add_slider_int(
                            label=f"##{slide_tag}",
                            tag=slide_tag,
                            default_value=val,
                            min_value=lo,
                            max_value=hi,
                            vertical=True,
                            height=200,
                            width=40,
                            callback=on_slide,
                            user_data=lbl_tag,
                        )
                        # lo marker
                        dpg.add_text(f"v {lo}",
                                     color=(108, 112, 134))
                        # param name — each word on its own line
                        for word in param.split("_"):
                            dpg.add_text(word, color=(166, 173, 200))

                    dpg.add_spacer(width=4)     # gap between cards

        return win_tag


# ==============================================================================
# Musical hierarchy instances
# ==============================================================================

global_meta = meta({
    "title":       [],
    "composer":    [],
    "genre":       [],
    "style":       [],
    "form":        [],
    "instruments": [],
    "tracks":      [],
})

track_meta = meta({
    "instrument":    [],
    "track":         [],
    "clef":          [],
    "transposition": [],
    "staff":         [],
    "effects":       [],
}, parent=global_meta)

section_meta = meta({
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

measure_meta = meta({
    "measure":           [],
    "time_signature":    [],
    "meter_numerator":   [],
    "meter_denominator": [],
}, parent=section_meta)

beat_meta = meta({
    "beat":        [],
    "subdivision": [],
    "tuplet":      [],
}, parent=measure_meta)

note_meta = meta({
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


# ==============================================================================
# BEGIN DEMO
# ==============================================================================

if __name__ == "__main__":

    dpg.create_context()

    # ── Global theme — Catppuccin Mocha ───────────────────────────────────
    with dpg.theme() as app_theme:
        with dpg.theme_component(dpg.mvAll):
            # backgrounds
            dpg.add_theme_color(dpg.mvThemeCol_WindowBg,           ( 30,  30,  46, 255))
            dpg.add_theme_color(dpg.mvThemeCol_ChildBg,            ( 49,  50,  68, 255))
            dpg.add_theme_color(dpg.mvThemeCol_PopupBg,            ( 30,  30,  46, 255))
            # frames
            dpg.add_theme_color(dpg.mvThemeCol_FrameBg,            ( 69,  71,  90, 255))
            dpg.add_theme_color(dpg.mvThemeCol_FrameBgHovered,     ( 88,  91, 112, 255))
            dpg.add_theme_color(dpg.mvThemeCol_FrameBgActive,      (108, 112, 134, 255))
            # sliders
            dpg.add_theme_color(dpg.mvThemeCol_SliderGrab,         (137, 180, 250, 255))
            dpg.add_theme_color(dpg.mvThemeCol_SliderGrabActive,   (166, 227, 161, 255))
            # buttons
            dpg.add_theme_color(dpg.mvThemeCol_Button,             ( 69,  71,  90, 255))
            dpg.add_theme_color(dpg.mvThemeCol_ButtonHovered,      ( 88,  91, 112, 255))
            dpg.add_theme_color(dpg.mvThemeCol_ButtonActive,       (108, 112, 134, 255))
            # text
            dpg.add_theme_color(dpg.mvThemeCol_Text,               (205, 214, 244, 255))
            dpg.add_theme_color(dpg.mvThemeCol_TextDisabled,       (108, 112, 134, 255))
            # title bar
            dpg.add_theme_color(dpg.mvThemeCol_TitleBg,            ( 17,  17,  27, 255))
            dpg.add_theme_color(dpg.mvThemeCol_TitleBgActive,      ( 49,  50,  68, 255))
            dpg.add_theme_color(dpg.mvThemeCol_TitleBgCollapsed,   ( 17,  17,  27, 255))
            # scrollbar
            dpg.add_theme_color(dpg.mvThemeCol_ScrollbarBg,        ( 17,  17,  27, 255))
            dpg.add_theme_color(dpg.mvThemeCol_ScrollbarGrab,      ( 69,  71,  90, 255))
            dpg.add_theme_color(dpg.mvThemeCol_ScrollbarGrabHovered,( 88, 91,  112, 255))
            # accents
            dpg.add_theme_color(dpg.mvThemeCol_Separator,          (137, 180, 250, 120))
            dpg.add_theme_color(dpg.mvThemeCol_ResizeGrip,         (137, 180, 250,  80))
            dpg.add_theme_color(dpg.mvThemeCol_ResizeGripHovered,  (137, 180, 250, 180))
            dpg.add_theme_color(dpg.mvThemeCol_ResizeGripActive,   (137, 180, 250, 255))
            # rounding & spacing
            dpg.add_theme_style(dpg.mvStyleVar_WindowRounding,     8)
            dpg.add_theme_style(dpg.mvStyleVar_FrameRounding,      6)
            dpg.add_theme_style(dpg.mvStyleVar_GrabRounding,       6)
            dpg.add_theme_style(dpg.mvStyleVar_ChildRounding,      6)
            dpg.add_theme_style(dpg.mvStyleVar_PopupRounding,      6)
            dpg.add_theme_style(dpg.mvStyleVar_ItemSpacing,        8,  8)
            dpg.add_theme_style(dpg.mvStyleVar_ItemInnerSpacing,   4,  4)
            dpg.add_theme_style(dpg.mvStyleVar_FramePadding,       6,  4)
            dpg.add_theme_style(dpg.mvStyleVar_WindowPadding,      12, 12)

    dpg.bind_theme(app_theme)

    # ── Viewport ──────────────────────────────────────────────────────────
    dpg.create_viewport(
        title="Musical Parameters",
        width=1400,
        height=660,
    )
    dpg.setup_dearpygui()

    # ── Open windows ──────────────────────────────────────────────────────
    meta.display(note_meta,    title="Note Level")
    meta.display(beat_meta,    title="Beat Level")
    meta.display(section_meta, title="Section Level")
    meta.display(measure_meta, title="Measure Level")

    # ── Console verification ───────────────────────────────────────────────
    section_meta["tempo"] = [120]
    section_meta["key"]   = [5]
    print("note depth          :", note_meta.depth())
    print("note sees tempo     :", note_meta["tempo"])
    print("note sees title     :", note_meta["title"])
    print("all keys from note  :", sorted(note_meta.all_keys()))

    # ── Run ───────────────────────────────────────────────────────────────
    dpg.show_viewport()
    dpg.start_dearpygui()
    dpg.destroy_context()

# ==============================================================================
# END DEMO
# ==============================================================================
