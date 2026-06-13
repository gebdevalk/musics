
import sys
from PyQt5.QtWidgets import *
from PyQt5.QtCore import *
# from aiosignal import Signal


# ----------------------------------------------------------------------
# SmartKnob: single component with short‑click zoom, long‑press live toggle
# ----------------------------------------------------------------------
class SmartKnob(QWidget):
    # Signal emitted when live mode is ON and the user turns the knob
    valueChanged = Signal(float)

    def __init__(self, name="Knob", min_val=0, max_val=100, default_val=50, parent=None):
        super().__init__(parent)
        self.name = name
        self.min_val = min_val
        self.max_val = max_val
        self.default_val = default_val
        self.live_mode = False # start in "cold" (no updates)
        self._original_min = None # stored for range restore
        self._original_max = None
        self._is_symmetrized = False
        self._press_timer = None
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5, 5, 5, 5)

        # Name label
        self.name_label = QLabel(self.name)
        self.name_label.setAlignment(Qt.AlignCenter)

        # Value label (shows current value and live mode indicator)
        self.value_label = QLabel(str(self.default_val))
        self.value_label.setAlignment(Qt.AlignCenter)
        self._update_value_label_style()

        # The knob itself (QDial)
        self.knob = QDial()
        self.knob.setMinimum(self.min_val)
        self.knob.setMaximum(self.max_val)
        self.knob.setValue(self.default_val)
        self.knob.setNotchesVisible(True)
        self.knob.valueChanged.connect(self._on_knob_turned)

        # Limit labels (show current min/max)
        self.low_label = QLabel(str(self.min_val))
        self.high_label = QLabel(str(self.max_val))
        limits_layout = QHBoxLayout()
        limits_layout.addWidget(self.low_label)
        limits_layout.addStretch()
        limits_layout.addWidget(self.high_label)

        # Assemble
        layout.addWidget(self.name_label)
        layout.addWidget(self.value_label)
        layout.addWidget(self.knob)
        layout.addLayout(limits_layout)

        # Timer for long press detection
        self._press_timer = QTimer(self)
        self._press_timer.setSingleShot(True)
        self._press_timer.timeout.connect(self._on_long_press)

        # Install event filter on the knob to catch middle‑button press/release
        self.knob.installEventFilter(self)

        # Update limit labels when knob's range changes (symmetrize/restore)
        self.knob.rangeChanged.connect(self._update_limits_display)

        # Initial temperature coloring (hot/cold background)
        self._update_temperature(self.default_val)

    # ------------------------------------------------------------------
    # Event handling for short click vs long press
    # ------------------------------------------------------------------
    def eventFilter(self, obj, event):
        if obj == self.knob and event.type() == QEvent.MouseButtonPress:
            if event.button() == Qt.MiddleButton:
                self._press_timer.start(500) # 500 ms long press threshold
                return True
        elif obj == self.knob and event.type() == QEvent.MouseButtonRelease:
            if event.button() == Qt.MiddleButton:
                if self._press_timer.isActive():
                    self._press_timer.stop()
                    self._on_short_click() # short click = release before timeout
                return True
        return super().eventFilter(obj, event)

    def _on_short_click(self):
        """Short middle‑click: symmetrize range (first click); restore original (second click)."""
        cur = self.knob.value()
        if not self._is_symmetrized:
            # First click: store current limits
            self._original_min = self.knob.minimum()
            self._original_max = self.knob.maximum()
            self._symmetrize_limits()
            self._is_symmetrized = True
        else:
            # Second click: restore original limits
            if self._original_min is not None and self._original_max is not None:
                self.knob.blockSignals(True)
                self.knob.setMinimum(self._original_min)
                self.knob.setMaximum(self._original_max)
                self.knob.blockSignals(False)
                # Clamp current value to restored range
                if cur < self._original_min:
                    cur = self._original_min
                elif cur > self._original_max:
                    cur = self._original_max
                self.knob.setValue(cur)
                self._is_symmetrized = False
                self._original_min = None
                self._original_max = None

    def _symmetrize_limits(self):
        """Adjust min/max to be symmetric around current value."""
        cur = self.knob.value()
        left_dist = cur - self.knob.minimum()
        right_dist = self.knob.maximum() - cur
        delta = min(left_dist, right_dist)
        new_min = cur - delta
        new_max = cur + delta
        if new_min != self.knob.minimum() or new_max != self.knob.maximum():
            self.knob.blockSignals(True)
            self.knob.setMinimum(new_min)
            self.knob.setMaximum(new_max)
            self.knob.blockSignals(False)
            self.knob.setValue(cur)

    def _on_long_press(self):
        """Toggle live mode: red text = live ON, black = live OFF."""
        self.live_mode = not self.live_mode
        self._update_value_label_style()
        # Refresh temperature to preserve background but update text color
        self._update_temperature(self.knob.value())

    # ------------------------------------------------------------------
    # UI update methods
    # ------------------------------------------------------------------
    def _update_value_label_style(self):
        if self.live_mode:
            self.value_label.setStyleSheet("color: red; font-weight: bold; background: transparent;")
        else:
            self.value_label.setStyleSheet("color: black; background: transparent;")

    def _update_limits_display(self, min_val, max_val):
        self.low_label.setText(str(min_val))
        self.high_label.setText(str(max_val))
        self._update_temperature(self.knob.value())

    def _update_temperature(self, value):
        """Optional hot/cold background: blue (cold) → green → red (hot)."""
        mn = self.knob.minimum()
        mx = self.knob.maximum()
        if mx == mn:
            ratio = 0.5
        else:
            ratio = (value - mn) / (mx - mn)
        if ratio < 0.5:
            r = int(2 * ratio * 255)
            g = int(2 * ratio * 255)
            b = 255
        else:
            r = 255
            g = int((1 - 2 * (ratio - 0.5)) * 255)
            b = 0
        bg_color = f"rgb({r},{g},{b})"
        text_color = "red" if self.live_mode else "black"
        self.value_label.setStyleSheet(
            f"color: {text_color}; background-color: {bg_color}; border-radius: 4px; padding: 4px;"
        )

    def _on_knob_turned(self, val):
        """Always update display; emit signal only if live mode is ON."""
        self.value_label.setText(str(val))
        self._update_temperature(val)
        if self.live_mode:
            self.valueChanged.emit(float(val))

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------
    def setValue(self, val):
        self.knob.setValue(val) # this triggers _on_knob_turned

    def value(self):
        return self.knob.value()

    def setLiveMode(self, enabled):
        self.live_mode = enabled
        self._update_value_label_style()
        self._update_temperature(self.knob.value())

    def isLiveMode(self):
        return self.live_mode


# ----------------------------------------------------------------------
# Model (runs in background thread to simulate concurrency)
# ----------------------------------------------------------------------
class AudioModel(QObject):
    valueChanged = Signal(float)

    def __init__(self):
        super().__init__()
        self._value = 0.0

    @pyqtSlot(float)
    def update_value(self, new_val):
        self._value = new_val
        # Simulate background processing (e.g., audio DSP)
        QTimer.singleShot(0, self._process)

    def _process(self):
        # Simulate a small workload (in real code you'd do useful work)
        QThread.msleep(20)
        self.valueChanged.emit(self._value)


# ----------------------------------------------------------------------
# Main window: assembles MVC with concurrency
# ----------------------------------------------------------------------
class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Smart Knob – Short click zoom, long press live toggle")
        self.setGeometry(100, 100, 320, 400)

        central = QWidget()
        self.setCentralWidget(central)
        layout = QVBoxLayout(central)

        # Create the standalone knob
        self.knob = SmartKnob("Volume", 0, 100, 50)
        layout.addWidget(self.knob)

        # Create model and move it to a separate thread
        self.model = AudioModel()
        self.worker_thread = QThread()
        self.model.moveToThread(self.worker_thread)
        self.worker_thread.start()

        # Connect knob → model (only when live mode is ON)
        self.knob.valueChanged.connect(self.model.update_value)

        # Optional: connect model back to knob to show "processed" value
        self.model.valueChanged.connect(self.knob.setValue)

        # Instructions
        info = QLabel(
            "<b>Usage:</b><br>"
            "• Turn knob → value updates always.<br>"
            "• <b>Middle‑click (short)</b> → zoom range symmetrically.<br>"
            "• <b>Middle‑click & hold (≥500 ms)</b> → toggle LIVE mode.<br>"
            "&nbsp;&nbsp;&nbsp;<span style='color:red'>Red text</span> = LIVE ON (sends to model).<br>"
            "&nbsp;&nbsp;&nbsp;Black text = LIVE OFF (local only)."
        )
        info.setWordWrap(True)
        layout.addWidget(info)
        layout.addStretch()

        self.statusBar().showMessage("Ready. Long press the knob to toggle live mode.")

    def closeEvent(self, event):
        self.worker_thread.quit()
        self.worker_thread.wait()
        event.accept()


# ----------------------------------------------------------------------
if __name__ == "__main__":
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec_())
