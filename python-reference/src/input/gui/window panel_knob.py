
import sys
from PyQt5.QtWidgets import *
from PyQt5.QtCore import Qt

# ========== LEAF COMPONENTS ==========

class ConfigurableKnob(QWidget):
    def __init__(self, name="Knob", min_val=0, max_val=100, default_val=50, parent=None):
        super().__init__(parent)
        self.name = name
        self.min_val = min_val
        self.max_val = max_val
        self.default_val = default_val
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5,5,5,5)
        self.name_label = QLabel(self.name)
        self.name_label.setAlignment(Qt.AlignCenter)
        self.value_label = QLabel(str(self.default_val))
        self.value_label.setAlignment(Qt.AlignCenter)
        self.knob = QDial()
        self.knob.setMinimum(self.min_val)
        self.knob.setMaximum(self.max_val)
        self.knob.setValue(self.default_val)
        self.knob.setNotchesVisible(True)
        self.knob.valueChanged.connect(self._on_value_changed)
        low_label = QLabel(str(self.min_val))
        high_label = QLabel(str(self.max_val))
        limits_layout = QHBoxLayout()
        limits_layout.addWidget(low_label)
        limits_layout.addStretch()
        limits_layout.addWidget(high_label)
        layout.addWidget(self.name_label)
        layout.addWidget(self.value_label)
        layout.addWidget(self.knob)
        layout.addLayout(limits_layout)

    def _on_value_changed(self, val):
        self.value_label.setText(str(val))

    def get_value(self):
        return self.knob.value()

    def set_value(self, val):
        self.knob.setValue(val)


class ConfigurableComboBox(QWidget):
    def __init__(self, label="Select", options=["Option 1"], default_index=0, parent=None):
        super().__init__(parent)
        self.label_text = label
        self.options = options
        self.default_index = default_index
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5,5,5,5)
        label_widget = QLabel(self.label_text)
        label_widget.setAlignment(Qt.AlignCenter)
        self.combo = QComboBox()
        self.combo.addItems(self.options)
        self.combo.setCurrentIndex(self.default_index)
        layout.addWidget(label_widget)
        layout.addWidget(self.combo)

    def get_value(self):
        return self.combo.currentText()

    def set_value(self, text):
        idx = self.combo.findText(text)
        if idx >= 0:
            self.combo.setCurrentIndex(idx)


class ConfigurableRangeControl(QWidget):
    """Upper / lower limit control using two spin boxes"""
    def __init__(self, label="Range", min_val=0, max_val=100, default_low=25, default_high=75, parent=None):
        super().__init__(parent)
        self.label_text = label
        self.min_bound = min_val
        self.max_bound = max_val
        self.default_low = default_low
        self.default_high = default_high
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5,5,5,5)
        label_widget = QLabel(self.label_text)
        label_widget.setAlignment(Qt.AlignCenter)

        # Horizontal box for two spin boxes
        spin_layout = QHBoxLayout()
        self.low_spin = QSpinBox()
        self.high_spin = QSpinBox()
        self.low_spin.setRange(self.min_bound, self.max_bound)
        self.high_spin.setRange(self.min_bound, self.max_bound)
        self.low_spin.setValue(self.default_low)
        self.high_spin.setValue(self.default_high)
        self.low_spin.valueChanged.connect(self._update_low)
        self.high_spin.valueChanged.connect(self._update_high)

        spin_layout.addWidget(QLabel("Low:"))
        spin_layout.addWidget(self.low_spin)
        spin_layout.addWidget(QLabel("High:"))
        spin_layout.addWidget(self.high_spin)

        layout.addWidget(label_widget)
        layout.addLayout(spin_layout)

    def _update_low(self, val):
        if val > self.high_spin.value():
            self.high_spin.setValue(val)

    def _update_high(self, val):
        if val < self.low_spin.value():
            self.low_spin.setValue(val)

    def get_value(self):
        return (self.low_spin.value(), self.high_spin.value())

    def set_value(self, low, high):
        self.low_spin.setValue(low)
        self.high_spin.setValue(high)


class ConfigurableCheckbox(QWidget):
    def __init__(self, label="Enable", default_checked=False, parent=None):
        super().__init__(parent)
        self.label_text = label
        self.default_checked = default_checked
        self._setup_ui()

    def _setup_ui(self):
        layout = QHBoxLayout(self)
        layout.setContentsMargins(5,5,5,5)
        self.checkbox = QCheckBox(self.label_text)
        self.checkbox.setChecked(self.default_checked)
        layout.addWidget(self.checkbox)
        layout.addStretch()

    def get_value(self):
        return self.checkbox.isChecked()

    def set_value(self, checked):
        self.checkbox.setChecked(checked)


class ConfigurableButton(QWidget):
    def __init__(self, label="Press", callback=None, parent=None):
        super().__init__(parent)
        self.label_text = label
        self.callback = callback
        self._setup_ui()

    def _setup_ui(self):
        layout = QHBoxLayout(self)
        layout.setContentsMargins(5,5,5,5)
        self.button = QPushButton(self.label_text)
        if self.callback:
            self.button.clicked.connect(self.callback)
        layout.addWidget(self.button)
        layout.addStretch()

    def set_callback(self, callback):
        self.callback = callback
        self.button.clicked.connect(callback)

    def get_value(self):
        # Buttons have no persistent value; return last click time or just None
        return None


class ConfigurableSlider(QWidget):
    def __init__(self, name="Slider", min_val=0, max_val=100, default_val=50, orientation=Qt.Horizontal, parent=None):
        super().__init__(parent)
        self.name = name
        self.min_val = min_val
        self.max_val = max_val
        self.default_val = default_val
        self.orientation = orientation
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5,5,5,5)
        self.name_label = QLabel(self.name)
        self.name_label.setAlignment(Qt.AlignCenter)
        self.value_label = QLabel(str(self.default_val))
        self.value_label.setAlignment(Qt.AlignCenter)
        self.slider = QSlider(self.orientation)
        self.slider.setMinimum(self.min_val)
        self.slider.setMaximum(self.max_val)
        self.slider.setValue(self.default_val)
        self.slider.valueChanged.connect(self._on_value_changed)
        low_label = QLabel(str(self.min_val))
        high_label = QLabel(str(self.max_val))
        limits_layout = QHBoxLayout()
        limits_layout.addWidget(low_label)
        limits_layout.addStretch()
        limits_layout.addWidget(high_label)
        layout.addWidget(self.name_label)
        layout.addWidget(self.value_label)
        layout.addWidget(self.slider)
        layout.addLayout(limits_layout)

    def _on_value_changed(self, val):
        self.value_label.setText(str(val))

    def get_value(self):
        return self.slider.value()

    def set_value(self, val):
        self.slider.setValue(val)


# ========== CONTAINER: ControlGroup ==========

class ControlGroup(QGroupBox):
    def __init__(self, title="Control Group", columns=3, parent=None):
        super().__init__(title, parent)
        self.columns = columns
        self.controls = [] # store all child controls
        self._layout = QGridLayout(self)
        self._row = 0
        self._col = 0

    def _add_control(self, widget):
        self.controls.append(widget)
        self._layout.addWidget(widget, self._row, self._col)
        self._col += 1
        if self._col >= self.columns:
            self._col = 0
            self._row += 1
        return widget

    def add_knob(self, name, min_val=0, max_val=100, default_val=50):
        return self._add_control(ConfigurableKnob(name, min_val, max_val, default_val, parent=self))

    def add_combo(self, label, options, default_index=0):
        return self._add_control(ConfigurableComboBox(label, options, default_index, parent=self))

    def add_range(self, label, min_val=0, max_val=100, default_low=25, default_high=75):
        return self._add_control(ConfigurableRangeControl(label, min_val, max_val, default_low, default_high, parent=self))

    def add_checkbox(self, label, default_checked=False):
        return self._add_control(ConfigurableCheckbox(label, default_checked, parent=self))

    def add_button(self, label, callback=None):
        return self._add_control(ConfigurableButton(label, callback, parent=self))

    def add_slider(self, name, min_val=0, max_val=100, default_val=50, orientation=Qt.Horizontal):
        return self._add_control(ConfigurableSlider(name, min_val, max_val, default_val, orientation, parent=self))

    def get_values(self):
        values = {}
        for ctrl in self.controls:
            if isinstance(ctrl, (ConfigurableKnob, ConfigurableSlider)):
                values[ctrl.name] = ctrl.get_value()
            elif isinstance(ctrl, ConfigurableComboBox):
                values[ctrl.label_text] = ctrl.get_value()
            elif isinstance(ctrl, ConfigurableRangeControl):
                values[ctrl.label_text] = ctrl.get_value()
            elif isinstance(ctrl, ConfigurableCheckbox):
                values[ctrl.label_text] = ctrl.get_value()
            # Buttons are excluded from values
        return values

    def configure(self, title=None, columns=None):
        if title is not None:
            self.setTitle(title)
        if columns is not None and columns != self.columns:
            self.columns = columns
            self._rebuild_layout()

    def _rebuild_layout(self):
        # Remove all widgets temporarily
        for ctrl in self.controls:
            self._layout.removeWidget(ctrl)
        self._row, self._col = 0, 0
        for ctrl in self.controls:
            self._layout.addWidget(ctrl, self._row, self._col)
            self._col += 1
            if self._col >= self.columns:
                self._col = 0
                self._row += 1


# ========== MAIN WINDOW (No Tabs, Scrollable) ==========

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Complete Control Panel")
        self.setGeometry(100, 100, 1000, 700)

        # Scroll area for many controls
        scroll = QScrollArea()
        self.setCentralWidget(scroll)
        container = QWidget()
        scroll.setWidget(container)
        scroll.setWidgetResizable(True)

        main_layout = QVBoxLayout(container)
        main_layout.setSpacing(20)

        # Create several ControlGroups with mixed components
        # Group 1: Instrument, Key, Scale (combos)
        group1 = ControlGroup("Instrument & Key", columns=3)
        group1.add_combo("Instrument", ["Piano", "Guitar", "Drums", "Bass", "Synth"], 0)
        group1.add_combo("Key", ["C", "G", "D", "A", "E", "B", "F#", "C#", "F", "Bb", "Eb", "Ab"], 0)
        group1.add_combo("Scale", ["Major", "Minor", "Pentatonic", "Blues", "Chromatic"], 0)
        main_layout.addWidget(group1)

        # Group 2: Volume, Pan (knobs) + Reverb (slider) + Mute (checkbox)
        group2 = ControlGroup("Mixer", columns=3)
        group2.add_knob("Volume", 0, 100, 80)
        group2.add_knob("Pan", -100, 100, 0)
        group2.add_slider("Reverb", 0, 100, 30)
        group2.add_checkbox("Mute", False)
        main_layout.addWidget(group2)

        # Group 3: Filter range (upper/lower limit) + Arpeggiator (checkbox) + BPM (knob)
        group3 = ControlGroup("Advanced", columns=2)
        group3.add_range("Filter Cutoff Range", 20, 20000, 200, 5000)
        group3.add_checkbox("Arpeggiator On", True)
        group3.add_knob("BPM", 60, 180, 120)
        main_layout.addWidget(group3)

        # Group 4: Demo of a button (with action)
        group4 = ControlGroup("Actions", columns=2)
        group4.add_button("Print All Values", self.print_all_values)
        group4.add_button("Reset Defaults", self.reset_defaults)
        main_layout.addWidget(group4)

        # Store groups to access later
        self.groups = [group1, group2, group3, group4]

        # Add a stretch at the bottom
        main_layout.addStretch()

    def print_all_values(self):
        print("\n=== Current Control Values ===")
        for group in self.groups:
            print(f"\n{group.title()}:")
            for name, val in group.get_values().items():
                print(f" {name}: {val}")

    def reset_defaults(self):
        # Reset knobs, sliders, combos, ranges, checkboxes to their initial defaults
        # For simplicity, we just reinitialize the groups (or you can store defaults)
        # Here we do a quick reset for demonstration:
        for group in self.groups:
            for ctrl in group.controls:
                if hasattr(ctrl, 'default_val'):
                    ctrl.set_value(ctrl.default_val)
                elif isinstance(ctrl, ConfigurableComboBox):
                    ctrl.set_value(ctrl.options[ctrl.default_index])
                elif isinstance(ctrl, ConfigurableRangeControl):
                    ctrl.set_value(ctrl.default_low, ctrl.default_high)
                elif isinstance(ctrl, ConfigurableCheckbox):
                    ctrl.set_value(ctrl.default_checked)
        self.statusBar().showMessage("Defaults restored", 2000)


if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec_())
