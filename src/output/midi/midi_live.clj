(ns output.midi.midi-live
  "Real-time MIDI output via javax.sound.midi Receiver.
   Auto-connects snd-virmidi ports to Fluidsynth on open.

   One-time system setup (run once):
     ./scripts/setup.sh

   Quick reconnect after qsynth restart:
     ./scripts/reconnect.sh

   Usage:
     (require '[output.midi.midi-live :as live])
     (def rcv (live/open-receiver))  ;; auto-connects to Fluidsynth
     (live/note-on rcv 0 60 100)
     (Thread/sleep 500)
     (live/note-off rcv 0 60)"
  (:require [clojure.java.shell :as shell])
  (:import [javax.sound.midi MidiSystem MidiDevice Receiver
            ShortMessage]))

;; ============================================================
;; Device discovery
;; ============================================================

(defn list-devices
  "Return a seq of [name, MidiDevice] pairs for all MIDI devices."
  []
  (for [info (MidiSystem/getMidiDeviceInfo)
        :let [dev (MidiSystem/getMidiDevice info)]]
    [(.getName info) dev]))

(defn find-writable-device
  "Find a device by name substring that can receive MIDI.
   Prefer devices with 'VirMIDI' or 'Virtual' in the name.
   Falls back to any writable non-synthesizer device."
  ([]
   (or (find-writable-device "VirMIDI")
       (find-writable-device "VirMIDI")
       (find-writable-device "Virtual")
       ;; Fallback: first writable device that isn't a synthesizer
       (some (fn [[_name dev]]
               (when (and (pos? (.getMaxReceivers dev))
                          (not (instance? javax.sound.midi.Synthesizer dev)))
                 dev))
             (list-devices))))
  ([substr]
   (some (fn [[name dev]]
           (when (and (.contains name substr)
                      (not (zero? (.getMaxReceivers dev))))
             dev))
         (list-devices))))

;; ============================================================
;; Receiver management
;; ============================================================

(defn- auto-connect!
  "Connect all VirMIDI ALSA sequencer ports to the first FLUID Synth port.
   Silently succeeds if already connected or ports not found."
  ([]
   (let [;; Parse aconnect -l output to find port numbers
         out      (:out (shell/sh "aconnect" "-l"))
         virmidi  (second (re-find #"client (\d+):.*VirMIDI" out))
         fluid    (second (re-find #"client (\d+):.*FLUID Synth" out))]
     (when (and virmidi fluid)
       (auto-connect! (Integer/parseInt virmidi) (Integer/parseInt fluid)))))
  ([virmidi-client fluid-client]
   (let [src  (str virmidi-client ":0")
         dest (str fluid-client ":0")
         out  (:out (shell/sh "aconnect" "-l"))
         already? (re-find (re-pattern (str "client " virmidi-client
                                            ".*Connecting To: " fluid-client))
                           out)]
     (when-not already?
       (shell/sh "aconnect" src dest)
       (println (str "[midi-live] Connected " src " -> " dest))))))

(defn open-receiver
  "Open a Receiver on the given device (or auto-detect VirMIDI).
   Automatically connects VirMIDI to Fluidsynth via aconnect.
   Returns the receiver."
  ([]
   (auto-connect!)
   (if-let [dev (find-writable-device)]
     (open-receiver dev)
     (throw (ex-info "No writable MIDI device found. Did you run ./scripts/setup.sh?"
                     {:devices (mapv first (list-devices))}))))
  ([^MidiDevice dev]
   (when-not (.isOpen dev)
     (.open dev))
   (println "[midi-live] Opened:" (.getName (.getDeviceInfo dev)))
   (.getReceiver dev)))

(defn close-receiver
  "Close the receiver's device."
  [^Receiver _rcv]
  ;; Receiver doesn't have close(); device does, but we don't hold it.
  ;; Best effort: just let GC handle it, or user can hold device ref.
  (println "[midi-live] Receiver released."))

;; ============================================================
;; Real-time MIDI messages
;; ============================================================

(defn- clamp-midi-byte
  "v clamped into MIDI's legal data-byte range [0, 127], printing a
   warning naming what v is and what it got clamped to whenever
   clamping actually changes the value. Used for pitch/velocity below
   rather than letting a stray out-of-range value (a runaway
   :transposition, an algorithm gone wrong, anything upstream) reach
   ShortMessage's own constructor -- its InvalidMidiDataException is
   uncaught here, and since note-on/note-off run inside an async
   voice's own goroutine, that exception silently kills just that one
   voice's thread with no clean message, not a single-note failure."
  [v what]
  (cond
    (< v 0)   (do (println (str "[midi-live] " what " " v " below MIDI's 0-127 range, clamped to 0")) 0)
    (> v 127) (do (println (str "[midi-live] " what " " v " above MIDI's 0-127 range, clamped to 127")) 127)
    :else     v))

(defn note-on
  "Send an immediate note-on. channel: 0-15, pitch/velocity: 0-127 --
   out-of-range pitch/velocity are clamped (see clamp-midi-byte) rather
   than thrown on."
  [^Receiver rcv channel pitch velocity]
  (.send rcv (ShortMessage. ShortMessage/NOTE_ON channel
                            (clamp-midi-byte pitch "pitch")
                            (clamp-midi-byte velocity "velocity")) -1))

(defn note-off
  "Send an immediate note-off. channel: 0-15, pitch: 0-127 -- pitch is
   clamped the same way note-on's is (see clamp-midi-byte)."
  [^Receiver rcv channel pitch]
  (.send rcv (ShortMessage. ShortMessage/NOTE_OFF channel (clamp-midi-byte pitch "pitch") 0) -1))

(defn program-change
  "Send an immediate program change."
  [^Receiver rcv channel program]
  (.send rcv (ShortMessage. ShortMessage/PROGRAM_CHANGE channel program 0) -1))

(defn control-change
  "Send an immediate control change. controller: 0-127, value: 0-127."
  [^Receiver rcv channel controller value]
  (.send rcv (ShortMessage. ShortMessage/CONTROL_CHANGE channel controller value) -1))

(defn pitch-bend
  "Send pitch bend. value: 0-16383 (8192 = center)."
  [^Receiver rcv channel value]
  (let [lsb (bit-and value 0x7F)
        msb (bit-shift-right value 7)]
    (.send rcv (ShortMessage. ShortMessage/PITCH_BEND channel lsb msb) -1)))

(defn all-notes-off
  "Send all-notes-off (CC 123) on the given channel."
  [^Receiver rcv channel]
  (control-change rcv channel 123 0))

;; ============================================================
;; High-level: play a sequence of notes with sleep timing
;; ============================================================

(defn play-phrase
  "Play a sequence of [pitch duration-ms velocity] triples in real time.
   Blocks until the phrase is done.
   Returns the total elapsed ms."
  ([rcv phrase]
   (play-phrase rcv 0 phrase))
  ([rcv channel phrase]
   (let [start (System/currentTimeMillis)]
     (doseq [[pitch dur vel] phrase]
       (note-on rcv channel pitch (or vel 80))
       (Thread/sleep (long dur))
       (note-off rcv channel pitch))
     (- (System/currentTimeMillis) start))))

(defn play-chord
  "Play a chord: all notes on simultaneously, hold for ms, then off."
  [rcv channel pitches velocity hold-ms]
  (doseq [p pitches] (note-on rcv channel p velocity))
  (Thread/sleep (long hold-ms))
  (doseq [p pitches] (note-off rcv channel p)))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  ;; List all devices
  (run! println (map first (list-devices)))

  ;; Open receiver and play a C major scale
  (let [rcv (open-receiver)]
    (program-change rcv 0 0)  ;; Acoustic Grand Piano
    (play-phrase rcv
      [[60 300 80] [62 300 80] [64 300 80] [65 300 80]
       [67 300 80] [69 300 80] [71 300 80] [72 500 80]])
    (all-notes-off rcv 0))

  ;; Play a chord
  (let [rcv (open-receiver)]
    (program-change rcv 0 41)  ;; Violin
    (play-chord rcv 0 [60 64 67] 80 2000)
    (all-notes-off rcv 0))
  )
