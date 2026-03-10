import dearpygui.dearpygui as dpg

from core.struct.domain.meta import note_meta, beat_meta, Meta, section_meta, measure_meta, PARAM_CONFIG


# ── Static display method ─────────────────────────────────────────────────

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

    # ── Display constants ─────────────────────────────────────────────────
    WINDOW_MIN_WIDTH = 500
    SLIDER_WIDTH = 20
    SLIDER_HEIGHT = 200
    CARD_SPACING = 4
    TEXT_SPACING = 6
    WINDOW_PADDING_X = 20
    WINDOW_PADDING_Y = 20
    WINDOW_CASCADE_OFFSET = 30



    # ── Unique tags ────────────────────────────────────────────────────
    Meta._win_count += 1
    uid = Meta._win_count
    win_tag = f"meta_win_{uid}"
    hide_tag = f"meta_hide_{uid}"
    close_tag = f"meta_close_{uid}"

    # ── Cascade offset so windows don't stack exactly ──────────────────
    offset = (uid - 1) * WINDOW_CASCADE_OFFSET
    pos_x = WINDOW_PADDING_X + offset
    pos_y = WINDOW_PADDING_Y + offset

    win_w = max(WINDOW_MIN_WIDTH, len(data) * (SLIDER_WIDTH + CARD_SPACING) + 40)
    win_h = 460

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
            no_close=True,  # we supply our own close button
    ):

        # ── Toolbar ────────────────────────────────────────────────────
        with dpg.group(horizontal=True):
            dpg.add_button(
                label="  Hide  ",
                tag=hide_tag,
                callback=on_hide,
                user_data=win_tag,
            )
            dpg.add_spacer(width=TEXT_SPACING)
            dpg.add_button(
                label="  Close  ",
                tag=close_tag,
                callback=on_close,
                user_data=win_tag,
            )

        # ── Per-button colours ─────────────────────────────────────────
        with dpg.theme() as hide_theme:
            with dpg.theme_component(dpg.mvButton):
                dpg.add_theme_color(dpg.mvThemeCol_Button, (166, 227, 161, 180))
                dpg.add_theme_color(dpg.mvThemeCol_ButtonHovered, (166, 227, 161, 255))
                dpg.add_theme_color(dpg.mvThemeCol_ButtonActive, (140, 200, 135, 255))
                dpg.add_theme_color(dpg.mvThemeCol_Text, (17, 17, 27, 255))
        dpg.bind_item_theme(hide_tag, hide_theme)

        with dpg.theme() as close_theme:
            with dpg.theme_component(dpg.mvButton):
                dpg.add_theme_color(dpg.mvThemeCol_Button, (243, 139, 168, 180))
                dpg.add_theme_color(dpg.mvThemeCol_ButtonHovered, (243, 139, 168, 255))
                dpg.add_theme_color(dpg.mvThemeCol_ButtonActive, (220, 100, 130, 255))
                dpg.add_theme_color(dpg.mvThemeCol_Text, (17, 17, 27, 255))
        dpg.bind_item_theme(close_tag, close_theme)

        dpg.add_separator()
        dpg.add_spacer(height=TEXT_SPACING)

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

                lbl_tag = f"val_{win_tag}_{col}"
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
                        height=SLIDER_HEIGHT,
                        width=SLIDER_WIDTH,
                        callback=on_slide,
                        user_data=lbl_tag,
                    )
                    # lo marker
                    dpg.add_text(f"v {lo}",
                                 color=(108, 112, 134))
                    # param name — each word on its own line
                    for word in param.split("_"):
                        dpg.add_text(word, color=(166, 173, 200))

                dpg.add_spacer(width=CARD_SPACING)  # gap between cards

    return win_tag


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
    display(note_meta, title="Note Level")
    display(beat_meta, title="Beat Level")
    display(section_meta, title="Section Level")
    display(measure_meta, title="Measure Level")

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
