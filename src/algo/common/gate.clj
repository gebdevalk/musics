(ns algo.common.gate
  "One general engine (gate) plus a small set of NAMED, registered
   criteria (core.wall/register-criterion!), replacing what used to be
   six separate, bespoke filter functions in algo.common.reshape (lo-
   filter/hi-filter/window-filter/pitch-class-filter/interval-filter/
   probability-filter, plus their own six -algo factory wrappers).

   The refactor was deliberately scoped to THIS case alone, not applied
   project-wide -- a survey of the whole algo/ tree found several other
   places with a superficially similar shape (algo.random's own lo-
   emph/mean-emph/hi-emph, algo.common.zfilter's own smooth/momentum/
   memory, algo.random's own rising/falling, random-walk/biased-walk,
   algo.common.trig's own cosr/sinr/tanr), but those are all plain
   building-block VALUES -- nothing anyone independently plays or
   configures via a preset, just occasionally handed to something else
   (register-distribution!, smooth-pitch-algo) when needed, which they
   already support fine as ordinary functions. Filters are different:
   they're independently PLAYED algorithms (:algo :loFilter, built via
   core.wall/build!), genuinely benefiting from being nameable/
   switchable the same real reason weighted-shuffle-algo's own
   distribution argument already works that way. Turning three one-line
   functions into a registry entry + factory + build! call trades
   trivial duplication for genuine indirection with no real payoff --
   this project's own stated rule
   (CLAUDE.md: 'three similar lines is better than a premature
   abstraction') is exactly why those OTHER cases were deliberately
   left alone.

   Two real simplifications from the version this replaces, both
   deliberate: gate always operates on a Leaf's FIRST pitch (no more
   per-chord-tone splitting -- a chord is judged, and kept or replaced,
   as one whole unit); and 'what happens to a rejected part' is now a
   genuine choice (:remove/:rest/:hold/custom), not hardcoded."
  (:require [core.domain.flat-domain :as d]
            [core.wall :as wall]
            [algo.random :as rand]))

;; ============================================================
;; gate -- the general engine
;; ============================================================

(defn- on-reject-fn
  "Resolve on-reject (a keyword or a custom fn) to a uniform (part
   last-sounding) -> replacement-or-nil action."
  [on-reject]
  (cond
    (fn? on-reject) on-reject
    (= :remove on-reject) (fn [_part _last-sounding] nil)
    (= :rest on-reject) (fn [part _last-sounding]
                           (d/rest* (:id part) (:context part) (:duration part)))
    (= :hold on-reject) (fn [part last-sounding]
                           (if last-sounding
                             (-> (d/leaf (:id part) (:context part) (:duration part)
                                         (:pitches last-sounding))
                                 (assoc :tied true))
                             ;; nothing has sounded yet -- nothing to hold onto
                             (d/rest* (:id part) (:context part) (:duration part))))
    :else (throw (ex-info "gate: on-reject must be :remove, :rest, :hold, or a fn"
                           {:given on-reject}))))

(defn gate
  "Gate parts (a seq of Leaf/Rest/Drum/container/etc -- the same shape
   a wall-fn always receives) by select-fn, deciding per-Leaf what
   happens to a rejected one via on-reject. Non-Leaf parts (Rest/Drum/
   container/Bar/:assignment/etc.) always pass through untouched and
   never affect raw-prev/last-sounding.

   select-fn: (part raw-prev) -> boolean. raw-prev is the immediately
   PRECEDING Leaf in the ORIGINAL sequence -- kept or not, nil at the
   very start -- matching the classic isorhythmic-style 'interval from
   the previous note' semantics: a rejected note still counts as 'the
   previous one' for the NEXT note's own check, same zip-over-raw-
   sequence idea algo.common.isorhythm/color-talea's own docstring
   describes for a different pairing.

   on-reject: :remove (drop -- the result can be shorter than parts),
   :rest (replace with a Rest of the same :id/:context/:duration),
   :hold (replace with a NEW Leaf at whatever part actually SOUNDS
   immediately before this one in the OUTPUT -- last-sounding, itself
   possibly an earlier :hold-tied note, so a run of rejected notes
   chains correctly back to the same real pitch -- :tied true, same
   duration; falls back to :rest if nothing has sounded yet), or a
   custom (part last-sounding) -> replacement-or-nil fn for anything
   else. last-sounding is DELIBERATELY a separate notion from raw-prev:
   raw-prev tracks the ORIGINAL sequence for select-fn's own criterion
   (unaffected by what gets kept/replaced), last-sounding tracks
   what an OUTPUT consumer would actually hear right before this note
   (needed for :hold to tie to the right pitch, and correctly chain
   through consecutive rejections)."
  [select-fn on-reject parts]
  (let [act (on-reject-fn on-reject)]
    (loop [remaining (seq parts) raw-prev nil last-sounding nil out []]
      (if (empty? remaining)
        out
        (let [part (first remaining)]
          (if (d/leaf? part)
            (if (select-fn part raw-prev)
              (recur (rest remaining) part part (conj out part))
              (let [replacement (act part last-sounding)]
                (recur (rest remaining) part (or replacement last-sounding)
                       (if replacement (conj out replacement) out))))
            (recur (rest remaining) raw-prev last-sounding (conj out part))))))))

(defn gate-algo
  "A core.wall FACTORY -- (fn [name criterion-spec on-reject] -> name) --
   resolving criterion-spec (e.g. [:lo 67]) against core.wall's own
   criteria registry (core.wall/resolve-criterion), then building (see
   core.wall/build-algo!, this factory's own last step) a gate wall-fn
   from the resolved select-fn and on-reject, stored under name.
     (register-criterion! :lo lo-criterion)
     (register-factory! :gate gate-algo)
     (build! :loFilter :gate [:lo 67] :remove)
     (play :verse :algo :loFilter)"
  [name criterion-spec on-reject]
  (let [select-fn (wall/resolve-criterion criterion-spec)]
    (wall/build-algo! name (fn [nodes _ctx-chain _voice] (gate select-fn on-reject nodes)))))

;; ============================================================
;; Criterion factories -- plain functions, NOT auto-registered
;; (nothing in this project ever is -- register whichever you actually
;; want reachable by name, e.g. (register-criterion! :lo lo-criterion)).
;; Each takes its own params and returns a select-fn, (part raw-prev)
;; -> boolean.
;; ============================================================

(defn- pitch-of [part] (first (:pitches part)))

(defn lo-criterion
  "Keep only pitches at or below cutoff -- the audio low-pass analogy:
   passes LOW, gates out HIGH."
  [cutoff]
  (fn [part _raw-prev] (<= (pitch-of part) cutoff)))

(defn hi-criterion
  "Keep only pitches at or above cutoff -- the audio high-pass analogy:
   passes HIGH, gates out LOW."
  [cutoff]
  (fn [part _raw-prev] (>= (pitch-of part) cutoff)))

(defn window-criterion
  "Keep only pitches within [lo hi] inclusive -- the audio band-pass
   analogy."
  [lo hi]
  (fn [part _raw-prev] (<= lo (pitch-of part) hi)))

(defn pitch-class-criterion
  "Keep only pitches whose pitch CLASS (mod 12) is in allowed-pcs --
   e.g. constrain a melody to a scale's own pitch classes regardless of
   octave."
  [allowed-pcs]
  (let [allowed (set (map #(mod % 12) allowed-pcs))]
    (fn [part _raw-prev] (contains? allowed (mod (pitch-of part) 12)))))

(defn interval-criterion
  "Keep a note only if its OWN pitch's melodic interval from raw-prev's
   own pitch is in allowed-intervals. The very first note (raw-prev
   nil) is always kept -- there's no previous interval to check yet."
  [allowed-intervals]
  (let [allowed (set allowed-intervals)]
    (fn [part raw-prev]
      (or (nil? raw-prev) (contains? allowed (- (pitch-of part) (pitch-of raw-prev)))))))

(defn probability-criterion
  "Keep each note with probability p (a Bernoulli coin flip per note,
   independent of pitch and of raw-prev). Draws from algo.random now
   (2026-09-05, algo.txt's own GAP 4), not bare clojure.core rand -- the
   last non-reproducible randomness site in this file, against every
   OTHER criterion here already being pure (no randomness at all)."
  [p]
  (fn [_part _raw-prev] (< (rand/rand-double) p)))
