# File: src/usr/gui/window_builder.py
import dearpygui.dearpygui as dpg

# ==================== STEP-BY-STEP BUILDER ====================
class StepByStepApp:
    """Build window step by step, starting empty"""

    def __init__(self):
        self.current_step = 0

    def run(self):
        """Run the step-by-step application"""
        dpg.create_context()
        dpg.create_viewport(title="Step-by-Step Building", width=900, height=700)

        # Step 1: Create completely empty window
        with dpg.window(label="Build Me Step by Step", tag="build_window", width=880, height=680):
            # Initially empty
            pass

            # Add a control panel to add content step by step
        with dpg.window(label="Controls", width=300, height=200, pos=[50, 50]):
            dpg.add_text("Add content to main window:")
            dpg.add_button(label="1. Add Title", callback=self._add_title)
            dpg.add_button(label="2. Add Volume Slider", callback=self._add_volume_slider)
            dpg.add_button(label="3. Add Tempo Slider", callback=self._add_tempo_slider)
            dpg.add_button(label="4. Add Algorithm Sliders", callback=self._add_algorithm_sliders)
            dpg.add_button(label="Clear All", callback=self._clear_window)

        dpg.setup_dearpygui()
        dpg.show_viewport()

        while dpg.is_dearpygui_running():
            dpg.render_dearpygui_frame()

        dpg.destroy_context()

    def _add_title(self):
        """Step 1: Add title to empty window"""
        with dpg.window(label="Build Me Step by Step", tag="build_window"):
            dpg.add_text("Music Control Panel", color=(0, 200, 255))
            dpg.add_separator()
        self.current_step = 1

    def _add_volume_slider(self):
        """Step 2: Add volume slider"""
        with dpg.window(label="Build Me Step by Step", tag="build_window"):
            dpg.add_slider_float(
                label="Master Volume",
                default_value=0.7,
                min_value=0.0,
                max_value=1.0,
                format="%.2f"
            )
        self.current_step = 2

    def _add_tempo_slider(self):
        """Step 3: Add tempo slider"""
        with dpg.window(label="Build Me Step by Step", tag="build_window"):
            dpg.add_slider_int(
                label="Tempo (BPM)",
                default_value=120,
                min_value=40,
                max_value=240
            )
        self.current_step = 3

    def _add_algorithm_sliders(self):
        """Step 4: Add algorithm sliders"""
        with dpg.window(label="Build Me Step by Step", tag="build_window"):
            dpg.add_slider_int(
                label="Beats",
                default_value=5,
                min_value=1,
                max_value=16
            )
            dpg.add_slider_int(
                label="Steps",
                default_value=8,
                min_value=1,
                max_value=32
            )
        self.current_step = 4

    def _clear_window(self):
        """Clear window back to empty state"""
        dpg.delete_item("build_window", children_only=True)
        self.current_step = 0

    # ==================== WINDOW TEMPLATE ====================


class WindowTemplate:
    """Template class - start with empty window, add content later"""

    def __init__(self, title):
        self.title = title
        self.content_added = False

    def create_empty(self):
        """Phase 1: Create completely empty window"""
        with dpg.window(label=self.title, tag=f"win_{self.title}"):
            # Window exists but has no widgets
            pass
        return self

    def add_content_later(self):
        """Phase 2: Add content when ready"""
        # You would add widgets here when you want content
        dpg.add_text(f"Content added to {self.title}")
        self.content_added = True
        return self


