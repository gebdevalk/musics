(ns input.midi
  "Real-time MIDI INPUT via overtone/midi-clj (the mirror image of
   output.midi.midi-live, which only ever sends). open-midi does two
   things at once, both starting the instant it's called and both
   stopping together at close-midi:

     1. midi-through -- every NOTE_ON/NOTE_OFF read from the device is
        forwarded immediately to musics' own connected output receiver
        (auto-(musics/connect)-ing first if nothing is connected yet,
        the exact lazy-connect pattern (musics/play) already uses)
        on a fixed channel (see thru-channel) -- so plugging a keyboard
        in and playing it is audible right away through the same
        Fluidsynth setup the engine itself plays through. No separate
        MIDI-out connection of its own; it deliberately rides the one
        (musics/connect) already opened.
     2. Every NOTE_ON/NOTE_OFF is ALSO put onto the returned handle's
        :events core.async channel, independent of #1 -- this is the
        raw stream input.midi-record listens to for record-midi. A
        sliding-buffer so a slow/absent reader can never back up real
        MIDI input.

   Every event's :ts is (System/currentTimeMillis) captured the instant
   this ns's own dispatch fn runs -- NOT overtone's own MidiMessage
   timestamp (a device-clock microsecond count the JVM reports as -1 on
   most real hardware), since wall-clock ms is what both live monitoring
   and record-midi's own duration math actually need.

   Only NOTE_ON/NOTE_OFF are looked at at all -- CC/clock/sysex/etc. are
   silently ignored, same tolerance output.midi.midi-live's own note-on/
   note-off give the opposite direction."
  (:require [overtone.midi :as omidi]
            [clojure.core.async :as a]
            [output.midi.midi-live :as live]
            [musics :as m]))

(def thru-channel
  "The fixed MIDI channel midi-through sends on -- a raw pass-through
   has no voice/container of its own to derive a channel from the way
   core.async-engine's per-voice channel pool does, so this is just a
   plain constant, not shared with that pool."
  0)

;; The most recently open-midi'd handle, mirroring musics/receiver's own
;; single-shared-atom pattern for output -- input.midi-record's
;; open-record reads this rather than taking a handle argument itself
;; (open-midi is always the separate, prior step that arms it), and
;; close-midi clears it back to nil.
(defonce *handle (atom nil))

(defn- forward-thru!
  [{:keys [command note velocity]}]
  (when (nil? @m/receiver) (m/connect))
  (case command
    :note-on  (live/note-on  @m/receiver thru-channel note velocity)
    :note-off (live/note-off @m/receiver thru-channel note)
    nil))

(defn list-inputs
  "Every currently available MIDI input source -- overtone.midi/
   midi-sources' own {:name :description ...} maps, for picking a
   name-substring to pass to open-midi."
  []
  (omidi/midi-sources))

(defn open-midi
  "Open a MIDI input device for reading and immediately start both
   midi-through and event delivery (see ns docstring) -- there is no
   separate start step. With no argument, overtone's own GUI device
   chooser pops up; a string argument matches a source's name/
   description as a case-insensitive regexp (overtone.midi/
   midi-find-device), same as output.midi.midi-live/find-writable-
   device's own substring matching on the output side.
   Returns a handle map ({:source :midi-receiver :events}) -- pass it
   to close-midi to stop everything this call started."
  ([] (open-midi nil))
  ([name-substring]
   (let [source (if name-substring
                  (omidi/midi-in name-substring)
                  (omidi/midi-in))
         events (a/chan (a/sliding-buffer 256))
         midi-receiver
         (omidi/midi-handle-events
          source
          (fn [{:keys [command note velocity]}]
            (when (#{:note-on :note-off} command)
              (let [evt {:command command :note note :velocity velocity
                         :ts (System/currentTimeMillis)}]
                (a/put! events evt)
                (forward-thru! evt)))))]
     (println "[input.midi] Opened:" (:name source))
     (reset! *handle {:source source :midi-receiver midi-receiver :events events})
     @*handle)))

(defn close-midi
  "Stop everything open-midi started on this handle: detaches the
   device's transmitter (no more thru forwarding, no more :events
   puts), closes the :events channel, releases the underlying device,
   and sends an all-notes-off on thru-channel in case a NOTE_OFF never
   arrived for whatever was last held down. Defaults to *handle (the
   most recently open-midi'd one) when called with no argument. Safe to
   call on an already-closed handle, or with nothing open at all."
  ([] (when-let [h @*handle] (close-midi h)))
  ([{:keys [source events]}]
   (when-let [tx (:transmitter source)]
     (.setReceiver tx nil))
   (when-let [dev (:device source)]
     (when (.isOpen dev) (.close dev)))
   (a/close! events)
   (when @m/receiver (live/all-notes-off @m/receiver thru-channel))
   (println "[input.midi] Closed:" (:name source))
   (when (= source (:source @*handle)) (reset! *handle nil))
   nil))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  (require '[input.midi :as midi])

  ;; List devices, then open one (or omit the arg for the GUI chooser)
  (midi/list-inputs)
  (def h (midi/open-midi))            ;; or (midi/open-midi "keyboard")

  ;; Play something on the keyboard -- should be audible immediately.

  (midi/close-midi h)
  )
