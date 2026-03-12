# File: src/usr/gui/window_base.py
import dearpygui.dearpygui as dpg

# ==================== CONSTANTS ====================
DEFAULT_WIDTH = 800
DEFAULT_HEIGHT = 600
DEFAULT_POS_X = 100
DEFAULT_POS_Y = 100


# ==================== BASE WINDOW CLASS ====================
class BaseWindow:
    """Base class for all windows with common functionality"""

    def __init__(self, title="Window", width=None, height=None, pos_x=None, pos_y=None):
        self.title = title
        self.width = width or DEFAULT_WIDTH
        self.height = height or DEFAULT_HEIGHT
        self.pos_x = pos_x or DEFAULT_POS_X
        self.pos_y = pos_y or DEFAULT_POS_Y
        self.window_tag = f"window_{title.replace(' ', '_').lower()}"
        self.is_visible = True

    def create(self, **kwargs):
        """Create the window - override in subclasses"""
        raise NotImplementedError("Subclasses must implement create()")

    def show(self):
        """Show the window"""
        if dpg.does_item_exist(self.window_tag):
            dpg.show_item(self.window_tag)
            self.is_visible = True

    def hide(self):
        """Hide the window"""
        if dpg.does_item_exist(self.window_tag):
            dpg.hide_item(self.window_tag)
            self.is_visible = False

    def toggle_visibility(self):
        """Toggle window visibility"""
        if self.is_visible:
            self.hide()
        else:
            self.show()

    def destroy(self):
        """Destroy the window"""
        if dpg.does_item_exist(self.window_tag):
            dpg.delete_item(self.window_tag)

        # ==================== EMPTY WINDOW ====================


class EmptyWindow(BaseWindow):
    """A simple empty window - perfect starting point"""

    def create(self, **kwargs):
        """Create an empty window with basic styling"""
        with dpg.window(
                label=self.title,
                tag=self.window_tag,
                width=self.width,
                height=self.height,
                pos=[self.pos_x, self.pos_y],
                no_move=False,
                no_resize=False,
                no_collapse=True,
                no_close=False,
                on_close=self.on_close,
                **kwargs
        ):
            # Add minimal content to show it's working
            dpg.add_text(f"Window: {self.title}")
            dpg.add_separator()
            dpg.add_text("This is an empty window template.")
            dpg.add_text("Add your content here.")

    def on_close(self, sender, app_data):
        """Handle window close event"""
        print(f"Window '{self.title}' closed")
        self.is_visible = False

    # ==================== SIMPLE FACTORY FUNCTIONS ====================


def create_empty_window_simple():
    """Create a completely empty window (minimal version)"""
    dpg.create_context()
    dpg.create_viewport(title='Empty Window', width=800, height=600)

    with dpg.window(label="Empty Window", tag="main_window"):
        pass  # Nothing here - completely empty

    dpg.setup_dearpygui()
    dpg.show_viewport()
    dpg.set_primary_window("main_window", True)

    while dpg.is_dearpygui_running():
        dpg.render_dearpygui_frame()


    dpg.destroy_context()


def simplest_empty_window():
    """The simplest possible empty window - 7 lines of code"""
    dpg.create_context()
    dpg.create_viewport()

    with dpg.window(label="Empty"):
        pass  # Nothing inside

    dpg.setup_dearpygui()
    dpg.show_viewport()

    while dpg.is_dearpygui_running():
        dpg.render_dearpygui_frame()

    dpg.destroy_context()