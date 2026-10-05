(ns musics.midi.file
  "MIDI file generation and playback via javax.sound.midi + aplaymidi.
   Usage: refer to source comment block at end of file."
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [musics.domain.resolve :as resolve])
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
;; Rendering a performance -- musics.events/events' output, as a file
;; ============================================================

(def ^:private ticks-per-sec 1000)

(defn- short-msg [cmd channel a b] (ShortMessage. (int cmd) (int channel) (int a) (int b)))

(defn events->sequence
  "A Sequence from timed events (musics.events/events), one track per
   voice (:path), at 1000 ticks a second. A :note's channel comes from a
   pool keyed on [program cc], like the live engine's, its program and
   CC sent when the channel is first taken (past 15 such timbres,
   channels are shared); a :drum keeps channel 9.
   A note starts at :t + :micro -- early is fine here -- moved and its
   velocity varied by :humanization (musics.domain.resolve/humanize,
   seeded by seed), and stops :dur-played later, unless :tied."
  [events & {:keys [seed] :or {seed 0}}]
  (let [s      (Sequence. Sequence/PPQ ticks-per-sec)
        rng    (java.util.Random. seed)
        tempo  (doto (MetaMessage.) (.setMessage 0x51 (byte-array [0x0F 0x42 0x40]) 3)) ; 60 bpm
        tracks (atom {})
        claims (atom {})
        pool   (vec (remove #{9} (range 16)))
        tick   #(long (Math/round (* (double %) ticks-per-sec)))]
    (.add (.createTrack s) (MidiEvent. tempo 0))
    (doseq [{:keys [kind path pitches program cc channel t dur-played tied] :as e}
            events
            :when (and (#{:note :drum} kind) (seq pitches))]
      (let [^Track tr (or (@tracks path) ((swap! tracks assoc path (.createTrack s)) path))
            [offset velocity] (resolve/humanize e #(.nextDouble rng))
            onset    (max 0.0 (+ t offset))
            on       (tick onset)
            ch       (if (= kind :drum)
                       channel
                       (or (@claims [program cc])
                           (let [ch (pool (mod (count @claims) (count pool)))]
                             (swap! claims assoc [program cc] ch)
                             (.add tr (MidiEvent. (short-msg ShortMessage/PROGRAM_CHANGE ch program 0) on))
                             (doseq [[n v] cc]
                               (.add tr (MidiEvent. (short-msg ShortMessage/CONTROL_CHANGE ch n v) on)))
                             ch)))]
        (doseq [p pitches]
          (.add tr (MidiEvent. (short-msg ShortMessage/NOTE_ON ch p velocity) on))
          (when-not tied
            (.add tr (MidiEvent. (short-msg ShortMessage/NOTE_OFF ch p 0) (tick (+ onset dur-played))))))))
    s))

;; ============================================================
;; File I/O
;; ============================================================

(defn write-midi
  [^Sequence seq ^File file]
  (MidiSystem/write seq (if (> (count (.getTracks seq)) 1) 1 0) file)
  file)

(defn write-events
  "Render timed events (musics.events/events) to a MIDI file; returns the
   File. Options as events->sequence."
  [events file & opts]
  (write-midi (apply events->sequence events opts) (io/file file)))

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