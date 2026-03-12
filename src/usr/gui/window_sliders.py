# File: src/usr/gui/window_sliders.py
import dearpygui.dearpygui as dpg
from .window_base import BaseWindow


# ==================== SLIDER WINDOW ====================
class SliderWindow(BaseWindow):
    """Window with sliders for music controls"""

    def __init__(self, title="Slider Window", width=800, height=600):
        super().__init__(title, width, height)
        self.slider_values = {}

    def create(self, **kwargs):
        """Create window with sliders"""
        with dpg.window(
                label=self.title,
                tag=self.window_tag,
                width=self.width,
                height=self.height,
                **kwargs
        ):
            self._add_sliders()

    def _add_sliders(self):
        """Add sliders to the window"""
        # Volume sliders
        with dpg.group():
            dpg.add_text("Audio Controls")
            dpg.add_slider_float(
                label="Volume",
                default_value=0.5,
                min_value=0.0,
                max_value=1.0,
                tag=f"{self.window_tag}_volume",
                callback=self._on_slider_change
            )
            dpg.add_slider_float(
                label="Pan",
                default_value=0.0,
                min_value=-1.0,
                max_value=1.0,
                tag=f"{self.window_tag}_pan",
                callback=self._on_slider_change
            )

        dpg.add_separator()

        # Music sliders
        with dpg.group():
            dpg.add_text("Music Parameters")
            dpg.add_slider_int(
                label="Tempo",
                default_value=120,
                min_value=40,
                max_value=240,
                tag=f"{self.window_tag}_tempo",
                callback=self._on_slider_change
            )
            dpg.add_slider_int(
                label="Octave",
                default_value=4,
                min_value=0,
                max_value=8,
                tag=f"{self.window_tag}_octave",
                callback=self._on_slider_change
            )

    def _on_slider_change(self, sender, app_data):
        """Handle slider changes"""
        slider_name = dpg.get_item_label(sender)
        self.slider_values[slider_name] = app_data
        print(f"{slider_name}: {app_data}")

    # ==================== SLIDER FACTORY FUNCTIONS ====================


def create_slider_window():
    """Create a window with music sliders"""
    dpg.create_context()
    dpg.create_viewport(title="Slider Controls", width=800, height=600)

    with dpg.window(label="Slider Controls", tag="main_window"):
        dpg.add_text("Volume Controls", color=(0, 200, 255))
        dpg.add_slider_float(
            label="Master Volume",
            default_value=0.7,
            min_value=0.0,
            max_value=1.0,
            format="%.2f",
            callback=lambda s, a: print(f"Volume: {a}")
        )

        dpg.add_spacer(height=10)

        dpg.add_text("Timing Controls", color=(0, 200, 255))
        dpg.add_slider_int(
            label="Tempo (BPM)",
            default_value=120,
            min_value=40,
            max_value=240,
            callback=lambda s, a: print(f"Tempo: {a}")
        )

        dpg.add_spacer(height=10)

        dpg.add_text("Algorithm Parameters", color=(0, 200, 255))
        dpg.add_slider_int(
            label="Beats (k)",
            default_value=5,
            min_value=1,
            max_value=16,
            callback=lambda s, a: print(f"Beats: {a}")
        )
        dpg.add_slider_int(
            label="Steps (n)",
            default_value=8,
            min_value=1,
            max_value=32,
            callback=lambda s, a: print(f"Steps: {a}")
        )

    dpg.setup_dearpygui()
    dpg.show_viewport()
    dpg.set_primary_window("main_window", True)

    while dpg.is_dearpygui_running():
        dpg.render_dearpygui_frame()

    dpg.destroy_context()


def simple_slider_window():
    """Simple approach: Start with empty window, add sliders directly"""
    dpg.create_context()
    dpg.create_viewport(title="Simple Sliders", width=600, height=400)

    with dpg.window(label="Simple Controls"):
        dpg.add_slider_float(label="Gain", default_value=0.5)
        dpg.add_slider_int(label="Pitch", default_value=64, min_value=0, max_value=127)
        dpg.add_slider_float(label="Reverb", default_value=0.3)

    dpg.setup_dearpygui()
    dpg.show_viewport()

    while dpg.is_dearpygui_running():
        dpg.render_dearpygui_frame()

    dpg.destroy_context()
