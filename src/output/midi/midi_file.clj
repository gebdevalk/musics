(ns output.midi.midi-file
  "MIDI file generation and playback via javax.sound.midi + aplaymidi.
   Usage: refer to source comment block at end of file."
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io])
  (:import [javax.sound.midi MidiSystem Sequence Track
            ShortMessage MetaMessage MidiEvent]
           [java.io File]))

;; ============================================================
;; MIDI event records
;; ============================================================

(defrecord MidiTEvent [tick channel pitch velocity duration])

(defrecord MidiProgramChange [tick channel program])

(defrecord MidiTempo [tick bpm])

(defrecord MidiTrack [events])

;; ============================================================
;; Convenience constructors
;; ============================================================

(defn note
  ([tick pitch duration] (->MidiTEvent tick 0 pitch 80 duration))
  ([tick channel pitch velocity duration]
   (->MidiTEvent tick channel pitch velocity duration)))

(defn program-change [tick channel program]
  (->MidiProgramChange tick channel program))

(defn tempo [tick bpm]
  (->MidiTempo tick bpm))

(defn track [& events]
  (->MidiTrack (vec (flatten events))))

;; ============================================================
;; Sequence building
;; ============================================================

(def ^:private default-division 480)

(defn- add-note-to-track!
  [^Track jtrack {:keys [tick channel pitch velocity duration]}]
  (let [on-msg  (ShortMessage. ShortMessage/NOTE_ON channel pitch velocity)
        off-msg (ShortMessage. ShortMessage/NOTE_OFF channel pitch 0)]
    (.add jtrack (MidiEvent. on-msg tick))
    (.add jtrack (MidiEvent. off-msg (+ tick duration)))))

(defn- add-program-change-to-track!
  [^Track jtrack {:keys [tick channel program]}]
  (let [msg (ShortMessage. ShortMessage/PROGRAM_CHANGE channel program 0)]
    (.add jtrack (MidiEvent. msg tick))))

(defn- add-tempo-to-track!
  [^Track jtrack {:keys [tick bpm]}]
  (let [mpq  (long (/ 60000000 bpm))
        data (byte-array [(unchecked-byte (bit-shift-right mpq 16))
                          (unchecked-byte (bit-shift-right mpq 8))
                          (unchecked-byte mpq)])
        msg  (MetaMessage.)]
    (.setMessage msg 0x51 data 3)
    (.add jtrack (MidiEvent. msg tick))))

(defn make-sequence
  [tracks & {:keys [division tempo-bpm]
             :or   {division default-division, tempo-bpm 120}}]
  (let [s (Sequence. Sequence/PPQ division)
        jt-tempo (.createTrack s)
        mpq  (long (/ 60000000 tempo-bpm))
        data (byte-array [(unchecked-byte (bit-shift-right mpq 16))
                          (unchecked-byte (bit-shift-right mpq 8))
                          (unchecked-byte mpq)])
        msg  (MetaMessage.)]
    (.setMessage msg 0x51 data 3)
    (.add jt-tempo (MidiEvent. msg 0))
    (doseq [mt tracks
            :let [jt (.createTrack s)]]
      (doseq [event (:events mt)]
        (cond
          (instance? MidiTEvent event)          (add-note-to-track! jt event)
          (instance? MidiProgramChange event) (add-program-change-to-track! jt event)
          (instance? MidiTempo event)         (add-tempo-to-track! jt event))))
    s))

;; ============================================================
;; File I/O
;; ============================================================

(defn write-midi
  [^Sequence seq ^File file]
  (MidiSystem/write seq 0 file)
  file)

(defn write-midi-temp
  [^Sequence seq]
  (let [f (File/createTempFile "musics-" ".mid")]
    (write-midi seq f)))

;; ============================================================
;; Playback via aplaymidi -> Fluidsynth
;; ============================================================

(def ^:private default-fluidsynth-port "128:0")

(defn play
  ([^Sequence seq]
   (play seq nil))
  ([^Sequence seq {:keys [port file]}]
   (let [port   (or port default-fluidsynth-port)
         f      (if file (io/file file) (write-midi-temp seq))
         path   (.getAbsolutePath f)
         result (shell/sh "aplaymidi" "-p" port path)]
     (when (nil? file)
       (try (.delete f) (catch Exception _)))
     result)))

;; ============================================================
;; High-level helpers
;; ============================================================

(defn pitches->track
  [pitches & {:keys [channel velocity] :or {channel 0 velocity 80}}]
  (loop [remaining pitches
         tick      0
         events    []]
    (if-let [[pitch dur] (first remaining)]
      (recur (rest remaining)
             (+ tick dur)
             (conj events (->MidiTEvent tick channel pitch velocity dur)))
      (->MidiTrack (vec events)))))

(defn chord->notes
  [tick channel pitches velocity duration]
  (vec (for [p pitches] (->MidiTEvent tick channel p velocity duration))))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  (let [notes [[60 240] [62 240] [64 240] [65 240]
               [67 240] [69 240] [71 240] [72 240]]
        trk   (pitches->track notes)
        seq   (sequence [trk])]
    (write-midi seq (java.io.File. "/tmp/scale.mid"))
    (play seq))

  (let [arp  (pitches->track [[60 360] [64 360] [67 360] [72 720]])
        seq  (sequence [arp])]
    (play seq))
  )