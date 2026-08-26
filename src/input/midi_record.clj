(ns input.midi-record
  "Record a performance from input.midi's event stream and translate it
   into musics-DSL text. open-record blocks the calling thread from the
   moment it's called until the recording ends -- the GUI wires it up
   inside a `future` (see gui.lib.state/start-record!), the same way
   this project's own voice-poll already runs a blocking loop off the
   render thread.

   Start is simply whatever the FIRST NOTE_ON read off the channel is
   -- there's no separate 'armed but waiting' state to model, since
   nothing is collected before that event anyway. End is a NOTE_ON
   below stop-note (MIDI 24, this DSL's own C1 -- confirmed with the
   user against General MIDI/Yamaha's differing C1=36); that note is
   itself never recorded.

   The recorded performance is reduced to a SINGLE line of chords/
   rests (group-chords/->segments below), not independent overlapping
   voices -- a deliberate scope limit, not an oversight: two notes
   whose onsets are more than chord-window-ms apart are always two
   separate segments in program order, even if they were actually held
   simultaneously on the keyboard. Fine for the common 'hum/play one
   line, possibly with chords' case this exists for.

   Rhythm is quantized in two passes: find-pulse picks a single
   best-fit tempo for the whole recording (a duration-weighted grid
   search, see its own docstring), then round-duration snaps each
   individual segment to the nearest of this DSL's own plain note
   values at that tempo. Triplets are deliberately out of scope (see
   roundable-lengths' own docstring) -- a known, documented gap, not a
   silent mis-round."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [common.music-data :as data]
            [input.midi :as midi]
            [input.reader.leaf-parser :as leaf]))

(def stop-note
  "Any NOTE_ON below this MIDI pitch ends a recording without itself
   being recorded. 24 = this DSL's own C1 ((inc octave)*12 with
   octave=1, see input.reader.leaf-parser/letter+octave->midi) -- NOT
   General MIDI/Yamaha's differing C1=36; confirmed with the user."
  24)

(def chord-window-ms
  "Note-ons at most this far apart record as one chord instead of two
   separate near-simultaneous notes."
  30)

;; ============================================================
;; Duration rounding -- reuses common.music-data/note-lengths (the
;; SAME table the rest of the domain model already uses) rather than a
;; second, independently-maintained copy of note-value fractions.
;; ============================================================

(def ^:private duration-digits
  "note-lengths key -> this DSL's own Duration digit spelling
   (musics.ebnf's DurationNum, '[1-9][0-9]{0,2}\\.*' -- a plain
   denominator with trailing dots for augmentation). Only the
   non-triplet keys are covered here on purpose -- see
   roundable-lengths."
  {:whole "1" :half "2" :quarter "4" :eighth "8"
   :sixteenth "16" :thirtysecond "32"
   :dotted-whole "1." :dotted-half "2." :dotted-quarter "4." :dotted-eighth "8."})

(def roundable-lengths
  "note-lengths filtered down to exactly the keys duration-digits above
   can spell -- triplets excluded deliberately: this DSL only ever
   writes a triplet by wrapping a whole GROUP of notes in `\\times 2/3
   { ... }` (see musics.ebnf's DurationExpr/DurationAtom, a different
   rule from a single Note's own plain Duration digit), never as one
   note's own Duration on its own. Grouping consecutive recorded notes
   into a \\times block is a real follow-up, not attempted by this
   pass -- a recorded triplet rounds to the nearest plain value
   instead, same as any other imprecisely-timed note would."
  (into {} (filter (fn [[k _]] (contains? duration-digits k)) data/note-lengths)))

(defn- nearest-length
  "The roundable-lengths [key {:length ...}] entry whose :length (a
   fraction of a whole note) is closest to ratio, plus the absolute
   error -- returns [key error]. Shared by find-pulse's own scoring and
   round-duration's actual rounding so the two can never disagree about
   what a given duration rounds to (an earlier version scored find-pulse
   against plain 32nd-note units instead of this same table, and could
   report a bpm as a PERFECT fit for a ratio -- e.g. 3/32, exactly
   between :sixteenth's 2/32 and :eighth's 4/32 -- that roundable-lengths
   itself has no entry for and so could never actually spell that well;
   confirmed live, not hypothetical). A tiny (1e-9 * length) tie-break
   prefers the finer (smaller) subdivision on an exact tie between two
   candidates, deterministically rather than at map-iteration-order's
   mercy."
  [ratio]
  (let [[k {:keys [length]}]
        (apply min-key
               (fn [[_ {:keys [length]}]]
                 (+ (Math/abs (- ratio (double length))) (* 1.0e-9 (double length))))
               roundable-lengths)]
    [k (Math/abs (- ratio (double length)))]))

(defn find-pulse
  "durations-ms (every recorded segment's own length, ms) -> the best-
   fit quarter-note BPM in [40, 240]. For each candidate BPM, every
   duration is expressed as a fraction of a whole note at that tempo
   and matched against the same roundable-lengths table round-duration
   itself rounds to (see nearest-length); the candidate's score is the
   sum, over every duration, of (duration * relative-error) -- 'a pulse
   duration based on total time, duration weighted numbers played and
   relative duration' from the spec: a long note contributes more error
   than a short one at the same relative mismatch, and playing more
   notes at a good tempo naturally outweighs a few bad ones just by
   adding more (small, correct) terms to the sum. Returns the BPM with
   the lowest score.

   Tempo detection is fundamentally octave-ambiguous -- a performance
   that fits perfectly at 90 BPM ALSO fits perfectly at 45/180/240 (the
   same durations, just relabeled as different note values), so ties
   (or near-ties, real human timing is never perfectly exact) are
   common, not a corner case. A tiny (1e-6 * bpm) tie-break term is
   added to the score, negligible next to any genuine rounding-error
   difference but enough to consistently prefer the SLOWEST tempo among
   near-equal fits -- the coarser, more legible note values a human
   would actually reach for, rather than an arbitrary highest-BPM
   candidate the search happened to reach last.
   A single-duration input has no real tempo to discover -- whichever
   BPM the search settles on for it is as good as any other, harmless
   for the degenerate one-note-recording case."
  [durations-ms]
  (apply min-key
         (fn [bpm]
           (let [whole-ms (/ 240000.0 bpm)]
             (+ (* 1.0e-6 bpm)
                (reduce + 0.0
                        (for [d durations-ms
                              :let [ratio (/ d whole-ms)
                                    [_ err] (nearest-length ratio)]]
                          (* d (/ err ratio)))))))
         (range 40 241)))

(defn round-duration
  "d-ms rounded to the nearest roundable-lengths entry at bpm's
   quarter-note tempo (see nearest-length), returned as this DSL's own
   Duration digit string (e.g. \"4\", \"8.\")."
  [bpm d-ms]
  (let [whole-ms (/ 240000.0 bpm)
        ratio    (/ d-ms whole-ms)
        [k _]    (nearest-length ratio)]
    (duration-digits k)))

;; ============================================================
;; Chording + rests
;; ============================================================

(defn group-chords
  "finished (a seq of {:pitch :onset :off}, any order) -> chronological
   chord groups ({:onset :off :pitches}, :off the group's OWN latest
   note-off so a chord never ends before its slowest voice does) --
   notes whose onsets are within chord-window-ms of each other collapse
   into one group, sorted by onset."
  [finished]
  (->> finished
       (sort-by :onset)
       (reduce (fn [groups {:keys [pitch onset off]}]
                 (if-let [g (peek groups)]
                   (if (<= (- onset (:onset g)) chord-window-ms)
                     (conj (pop groups)
                           (-> g (update :pitches conj pitch) (update :off max off)))
                     (conj groups {:onset onset :off off :pitches [pitch]}))
                   (conj groups {:onset onset :off off :pitches [pitch]})))
               [])))

(defn ->segments
  "Chord groups (see group-chords) -> a flat, chronological seq of
   {:type :note :dur :pitches} / {:type :rest :dur} -- a rest is
   inserted for every gap between one group's end and the next group's
   onset."
  [groups]
  (loop [[g & more] groups prev-end nil out (transient [])]
    (if g
      (let [gap (when prev-end (- (:onset g) prev-end))
            out (if (and gap (pos? gap)) (conj! out {:type :rest :dur gap}) out)
            out (conj! out {:type :note :dur (- (:off g) (:onset g)) :pitches (:pitches g)})]
        (recur more (:off g) out))
      (persistent! out))))

;; ============================================================
;; Text generation
;; ============================================================

(defn pitch-text
  "A recorded MIDI int as this DSL's own absolute pitch spelling
   (uppercase letter, sharp accidental for a black key, octave digit
   -- see input.reader.leaf-parser/midi->spelling)."
  [midi]
  (let [{:keys [letter accidental octave]} (leaf/midi->spelling midi)]
    (str (str/upper-case (str letter)) accidental octave)))

(defn ->musics-text
  "segments (see ->segments) + the chosen bpm (+ an optional GM program
   int for an !instrument:) -> a complete, parseable musics-text string:
   one [ ] Sequence opening with !tempo:/!instrument:, its own context
   -- NOT a bare top-level instruction before the [ -- since a bare
   Instruction is deliberately not a valid TopElement on its own (see
   CLAUDE.md's 'ROOT read-only' section); confirmed live, a leading
   \"!tempo:120\\n[ ... ]\" fails to parse at all where \"[ !tempo:120
   ... ]\" parses cleanly.
   A single Note needs the disambiguating '/' musics.ebnf's own OctaveAbs
   comment describes (Pitch immediately followed by a Duration digit,
   e.g. \"C4/4\") -- a Chord's pitches don't (ChordPitches requires
   whitespace between them), and neither does a Chord's own trailing
   Duration (preceded by '>', never a digit)."
  [segments bpm instrument-prog]
  (let [body (str/join " "
               (for [{:keys [type dur pitches]} segments
                     :let [dd (round-duration bpm dur)]]
                 (case type
                   :rest (str "r" dd)
                   :note (if (= 1 (count pitches))
                           (str (pitch-text (first pitches)) "/" dd)
                           (str "<" (str/join " " (map pitch-text pitches)) ">" dd)))))]
    (str "[ !tempo:" (long (Math/round (double bpm)))
         (when instrument-prog (str " !instrument:" instrument-prog))
         (when (seq body) (str " " body))
         " ]\n")))

;; ============================================================
;; Recording
;; ============================================================

(defn- resolve-instrument
  [instrument]
  (cond
    (nil? instrument) nil
    (number? instrument) (long instrument)
    (or (keyword? instrument) (string? instrument))
    (or (:prog (get data/gm-sound-set (keyword instrument)))
        (throw (ex-info (str "Unknown instrument: " instrument) {:instrument instrument})))
    :else (throw (ex-info (str "Unrecognized instrument arg: " (pr-str instrument)) {}))))

(defn- close-active
  "Every still-held note in active ({pitch {:onset ...}}) closed off at
   ts, as if a NOTE_OFF for each had just arrived -- used once
   recording ends (stop-note or the input channel closing) so a note
   still being held doesn't simply vanish from the recording."
  [active ts]
  (mapv (fn [[pitch {:keys [onset]}]] {:pitch pitch :onset onset :off ts}) active))

(defn- finalize
  [finished instrument-prog]
  (if (empty? finished)
    (->musics-text [] 120 instrument-prog)
    (let [segments (->segments (group-chords finished))
          bpm      (find-pulse (map :dur segments))]
      (->musics-text segments bpm instrument-prog))))

;; Set to a fresh channel at the start of every open-record call, closed
;; by stop-record! to break a still-running recording's own blocking
;; loop from another thread (the GUI's Stop button) -- see open-record's
;; own use of a/alts!! below. defonce so reloading this ns mid-recording
;; doesn't orphan whatever's currently listening on the old value.
(defonce ^:private *cancel-chan (atom nil))

(defn stop-record!
  "Manually end whatever open-record call is currently running, as if
   the stop-note had just been played -- closes the channel
   open-record's own loop is also listening on via a/alts!!. A no-op if
   nothing is currently recording."
  []
  (when-let [c @*cancel-chan] (a/close! c))
  nil)

(defn open-record
  "Block until a performance is recorded and return it as musics text
   (see ns docstring for start/stop and the quantization it applies).
   Ends on whichever comes first: a NOTE_ON below stop-note, or a call
   to stop-record! (both close off any still-held notes at that exact
   moment, same as a real NOTE_OFF would). Requires
   (input.midi/open-midi) to already be open -- this listens on its
   :events channel, it never opens a device of its own.
   instrument, if given, is either a raw GM program int or a keyword/
   string name looked up in common.music-data/gm-sound-set (the same
   table gui.lib.data/instruments is itself built from) -- written into
   the generated text's own !instrument: header."
  ([] (open-record nil))
  ([instrument]
   (let [handle (or @midi/*handle
                     (throw (ex-info "No MIDI input open -- call (input.midi/open-midi) first."
                                     {})))
         events      (:events handle)
         prog        (resolve-instrument instrument)
         cancel-chan (a/chan)]
     (reset! *cancel-chan cancel-chan)
     (try
       (loop [active {} finished []]
         (let [[evt port] (a/alts!! [events cancel-chan])]
           (cond
             (or (nil? evt) (= port cancel-chan))
             (finalize (into finished (close-active active (System/currentTimeMillis))) prog)

             (and (= (:command evt) :note-on) (< (:note evt) stop-note))
             (finalize (into finished (close-active active (:ts evt))) prog)

             (= (:command evt) :note-on)
             (recur (assoc active (:note evt) {:onset (:ts evt) :velocity (:velocity evt)})
                    finished)

             (= (:command evt) :note-off)
             (if-let [{:keys [onset]} (get active (:note evt))]
               (recur (dissoc active (:note evt))
                      (conj finished {:pitch (:note evt) :onset onset :off (:ts evt)}))
               (recur active finished))

             :else (recur active finished))))
       (finally
         (a/close! cancel-chan)
         (compare-and-set! *cancel-chan cancel-chan nil))))))

;; ============================================================
;; REPL smoke-test
;; ============================================================

(comment
  (require '[input.midi :as midi] '[input.midi-record :as rec])
  (midi/open-midi)
  (def text (rec/open-record))   ;; blocks -- play a phrase, end on a note below C1
  (println text)
  (midi/close-midi)
  )
