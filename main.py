import dearpygui.dearpygui as dpg
from src.usr.gui import (
    create_empty_window_simple,
    simplest_empty_window,
    create_slider_window,
    simple_slider_window,
    StepByStepApp,
    SliderWindow,
    EmptyWindow
)


def demo_all_windows():
    """Demo all window types in one application"""
    dpg.create_context()
    dpg.create_viewport(title="GUI Demo", width=1200, height=800)

    # Create multiple windows
    with dpg.window(label="Control Panel", width=300, height=200, pos=[50, 50]):
        dpg.add_text("Window Demos")
        dpg.add_button(label="Empty Window", callback=lambda: _create_empty_window())
        dpg.add_button(label="Slider Window", callback=lambda: _create_slider_window())
        dpg.add_button(label="Step-by-Step", callback=lambda: _create_step_by_step())

    dpg.setup_dearpygui()
    dpg.show_viewport()

    while dpg.is_dearpygui_running():
        dpg.render_dearpygui_frame()

    dpg.destroy_context()


def _create_empty_window():
    """Create an empty window"""
    empty_win = EmptyWindow("Empty Demo", 400, 300, 400, 50)
    empty_win.create()


def _create_slider_window():
    """Create a slider window"""
    slider_win = SliderWindow("Slider Demo", 400, 300, 400, 400)
    slider_win.create()


def _create_step_by_step():
    """Create step-by-step builder"""
    # This would need to be handled differently since StepByStepApp.run()
    # creates its own application context
    print("Step-by-Step builder needs to run separately")


def run_single_demo():
    """Run a single demo (choose one)"""
    # Uncomment one of these:

    # 1. Simple empty window
    # create_empty_window_simple()

    # 2. Minimal empty window
    # simplest_empty_window()

    # 3. Slider window
    # create_slider_window()

    # 4. Simple slider window
    # simple_slider_window()

    # 5. Step-by-step builder
    app = StepByStepApp()
    app.run()


if __name__ == "__main__":
    # Choose one:

    # Option 1: Run single demo
    run_single_demo()

    # Option 2: Demo all windows in one app
    # demo_all_windows()
