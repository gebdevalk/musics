# File: src/input/gui/slider_window.py (updated)
"""                                                                                                                                                                                                                                 
Specialized slider window functionality.
"""
import dearpygui.dearpygui as dpg
from .window_sliders import SliderWindow

# ==================== SPECIALIZED SLIDER CLASSES ====================
class AdvancedSliderWindow(SliderWindow):
    """Extended slider window with additional controls"""

    def _add_sliders(self):
        """Override to add more advanced sliders"""
        super()._add_sliders()

        # Add additional controls
        dpg.add_separator()

        with dpg.group():
            dpg.add_text("Advanced Controls", color=(255, 200, 0, 255))
            dpg.add_slider_float(
                label="Reverb Mix",
                default_value=0.3,
                min_value=0.0,
                max_value=1.0,
                tag=f"{self.window_tag}_reverb",
                callback=self._on_slider_change
            )
            dpg.add_slider_float(
                label="Delay Feedback",
                default_value=0.5,
                min_value=0.0,
                max_value=0.95,
                tag=f"{self.window_tag}_delay",
                callback=self._on_slider_change
            )

        # ==================== MAIN ====================


if __name__ == "__main__":
    # Demo specialized slider window
    dpg.create_context()
    dpg.create_viewport(title="Advanced Sliders", width=800, height=600)

    advanced_win = AdvancedSliderWindow("Advanced Music Controls")
    advanced_win.create()

    dpg.setup_dearpygui()
    dpg.show_viewport()

    while dpg.is_dearpygui_running():
        dpg.render_dearpygui_frame()

    dpg.destroy_context()