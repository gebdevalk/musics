;; counterpoint.clj
;; Clojure port of python-reference/src/algorithm/polyphony_generator.py
;; -- CounterpointEngine, a multi-voice generator combining loose motivic
;; imitation with species-counterpoint rule checking (Jeppesen-style):
;; no parallel fifths/octaves, consonance on every beat against every
;; already-placed voice.
;;
;; Each voice is generated in two passes. First, independently: a voice
;; either follows a motif (a short pitch-step + duration pattern, like a
;; fugue subject) loosely -- at each beat with probability :strictness
;; it takes the motif's own step, otherwise it falls back to a plain
;; :shape envelope (or 0) -- or just follows :shape directly with no
;; motif at all. Density envelopes subdivide each beat into sub-notes.
;; Second, every voice after the first is re-derived against all
;; earlier (already-finalized) voices: at each note, candidate pitches
;; near its own original target (within :tolerance) are filtered to
;; ones that stay consonant against every earlier voice and avoid
;; parallel fifths/octaves with the previous interval, then one is
;; picked at random from whatever survives.
;;
;; The reference this ports carries a little dead state -- a computed
;; but never-read motif-end-beat, a current-pitch reassignment that's
;; never read again after the very first note, an unused prev-new
;; local -- none of that is reproduced here, it has no effect on the
;; output either way.

(ns algo.melodic.counterpoint
  (:require [algo.random :as rand]))

(def default-rules
  {:no-parallel-fifths?   true
   :no-parallel-octaves?  true
   :consonance-on-strong? true})

;; ── Pitch selection primitives ───────────────────────────────

(defn- in-range? [[lo hi] p] (<= lo p hi))

(defn- choose-start-pitch [scale pitch-range]
  (rand/choose (vec (filter #(in-range? pitch-range %) scale))))

(defn- nearest-scale-pitch [scale pitch]
  (apply min-key #(Math/abs (double (- % pitch))) scale))

(defn- closest-allowed-pitch [scale pitch-range tolerance target]
  (let [allowed (filter #(and (<= (Math/abs (double (- % target))) tolerance) (in-range? pitch-range %))
                         scale)
        allowed (if (seq allowed) allowed [(nearest-scale-pitch scale target)])]
    (rand/choose (vec allowed))))

;; ── Per-beat sub-note duration shaping ───────────────────────

(defn- randomize-beat-subdurations
  "ideal-dur (the evenly-divided per-sub-note duration) reshaped into
   density values that still sum to (* ideal-dur density) (the whole
   beat), but front-loaded by :long-first-bias (0.0 = even, higher =
   more front-loaded) -- a geometric decay, not an actual random draw
   despite the name this ports."
  [ideal-dur density params]
  (let [bias (get params :long-first-bias 0.0)]
    (if (or (<= bias 0) (= density 1))
      (vec (repeat density ideal-dur))
      (let [raw-ratios (reductions (fn [r _] (* r (- 1 (* bias 0.5)))) 1.0 (range (dec density)))
            total (reduce + raw-ratios)]
        (mapv #(* (/ % total) ideal-dur density) raw-ratios)))))

;; ── Voice-spec normalization ─────────────────────────────────

(defn- build-motif-steps
  [n-beats {:keys [steps] :as _motif} start-beat transpose]
  (reduce (fn [arr [i step]]
            (let [beat-idx (int (+ start-beat i))]
              (if (< beat-idx n-beats) (assoc arr beat-idx (+ step transpose)) arr)))
          (vec (repeat n-beats nil))
          (map-indexed vector steps)))

(defn- normalize-voice-spec
  [n-beats spec]
  (let [density-envelope (get spec :density-envelope (vec (repeat n-beats 1)))
        _ (when (not= (count density-envelope) n-beats)
            (throw (ex-info "density-envelope length mismatch" {:expected n-beats :got (count density-envelope)})))
        spec (assoc spec
                    :density-envelope density-envelope
                    :tolerance (get spec :tolerance 3)
                    :rhythm-params (get spec :rhythm-params {}))]
    (if-let [motif (:motif spec)]
      (let [motif-total (reduce + (:durations motif))
            _ (when (> (Math/abs (- motif-total (Math/round motif-total))) 1e-6)
                (throw (ex-info "Motif total duration must be integer beats" {:total motif-total})))
            start-beat (get spec :delay-beats 0.0)
            transpose (get spec :transpose 0)]
        (assoc spec
               :motif-steps (build-motif-steps n-beats motif start-beat transpose)
               :strictness (get spec :strictness 0.7)))
      (if (contains? spec :shape)
        (assoc spec :motif-steps nil)
        (throw (ex-info "Voice needs either :shape or :motif" {:spec spec}))))))

;; ── Pass 1: independent per-voice generation ─────────────────

(defn- expand-voice
  "[sub-pitches sub-durs] for one voice, generated independently of every
   other voice (rule-checking is a separate pass, see generate)."
  [scale pitch-range beat-durations spec]
  (let [n-beats (count beat-durations)
        densities (:density-envelope spec)
        shape (get spec :shape (vec (repeat n-beats 0)))
        motif-steps (:motif-steps spec)
        strictness (get spec :strictness 0.0)
        tolerance (:tolerance spec)
        rhythm-params (:rhythm-params spec)]
    (reduce
     (fn [[sub-pitches sub-durs] beat-idx]
       (let [beat-dur (nth beat-durations beat-idx)
             density (nth densities beat-idx)
             _ (when-not (pos? density)
                 (throw (ex-info "Density must be positive" {:beat-idx beat-idx :density density})))
             motif-step (when motif-steps (nth motif-steps beat-idx))
             target-step (if (and motif-step (< (rand/rand-double) strictness))
                           motif-step
                           (if (< beat-idx (count shape)) (nth shape beat-idx) 0))
             step-total (or target-step 0)
             sub-step (/ step-total density)
             sub-dur (/ beat-dur density)
             sub-durs-beat (if (get rhythm-params :randomize?)
                             (randomize-beat-subdurations sub-dur density rhythm-params)
                             (vec (repeat density sub-dur)))]
         (reduce
          (fn [[sub-pitches sub-durs] sub-idx]
            (let [pitch (if (empty? sub-pitches)
                          (choose-start-pitch scale pitch-range)
                          (closest-allowed-pitch scale pitch-range tolerance (+ (peek sub-pitches) sub-step)))]
              [(conj sub-pitches pitch) (conj sub-durs (nth sub-durs-beat sub-idx))]))
          [sub-pitches sub-durs]
          (range density))))
     [[] []]
     (range n-beats))))

;; ── Pass 2: species-counterpoint rule enforcement ────────────

(defn- consonant-interval? [interval] (contains? #{3 4 8 9} interval))

(defn- violates-rules?
  [rules new-voice-so-far voice-pitches i p]
  (let [interval (mod (Math/abs (- p (nth voice-pitches i))) 12)
        prev-interval (when (pos? i)
                         (mod (Math/abs (- (peek new-voice-so-far) (nth voice-pitches (dec i)))) 12))]
    (or (and (:consonance-on-strong? rules) (not (consonant-interval? interval)))
        (and (:no-parallel-fifths? rules) (pos? i) (= prev-interval 7) (= interval 7))
        (and (:no-parallel-octaves? rules) (pos? i) (= prev-interval 0) (= interval 0)))))

(defn- add-voice-against-all
  "New voice's own pitches, one per target in target-pitches, each
   chosen to stay within tolerance of its own target while satisfying
   rules against every voice in existing-pitches-by-voice (a seq of
   earlier, already-finalized voices' own pitch sequences)."
  [scale pitch-range rules tolerance existing-pitches-by-voice target-pitches]
  (reduce
   (fn [new-voice i]
     (let [target (nth target-pitches i)
           candidates (filter (fn [p]
                                 (and (in-range? pitch-range p)
                                      (<= (Math/abs (double (- p target))) tolerance)
                                      (not-any? #(violates-rules? rules new-voice % i p)
                                                existing-pitches-by-voice)))
                               scale)
           pitch (if (seq candidates)
                   (rand/choose (vec candidates))
                   (closest-allowed-pitch scale pitch-range tolerance target))]
       (conj new-voice pitch)))
   []
   (range (count target-pitches))))

;; ── Top level ─────────────────────────────────────────────────

(defn generate
  "Generate n voices of [pitch duration] pairs from beat-durations (a
   seq of per-beat durations, in quarters) and voice-specs (one map per
   voice -- see the namespace docstring). scale is a seq of allowed
   pitches (need not be sorted); pitch-range is [lo hi]; rules defaults
   to default-rules. Every voice must resolve to the same total number
   of sub-notes (an equal sum of :density-envelope across voices) --
   this is required for the note-against-note rule checking."
  ;; Rules are a best-effort FILTER, not a hard guarantee: when a note
  ;; has zero candidates satisfying both tolerance and every rule at
  ;; once, generate falls back to the nearest allowed pitch regardless
  ;; of rule compliance, same as the reference this ports -- confirmed
  ;; live against the actual Python, this genuinely happens even with
  ;; a wide tolerance if scale only offers a handful of absolute
  ;; pitches (three-voice-simultaneous consonance is a much tighter
  ;; constraint than it looks). A scale spanning several octaves makes
  ;; real candidates far more likely to exist.
  ([scale pitch-range beat-durations voice-specs]
   (generate scale pitch-range default-rules beat-durations voice-specs))
  ([scale pitch-range rules beat-durations voice-specs]
   (let [scale (vec (sort scale))
         n-beats (count beat-durations)
         specs (mapv #(normalize-voice-spec n-beats %) voice-specs)
         expansions (mapv #(expand-voice scale pitch-range beat-durations %) specs)
         voice-sub-pitches (mapv first expansions)
         voice-sub-durs (mapv second expansions)
         total-sub-notes (count (first voice-sub-pitches))]
     (when-not (every? #(= total-sub-notes (count %)) voice-sub-pitches)
       (throw (ex-info "Voices produced different sub-note counts"
                        {:counts (mapv count voice-sub-pitches)})))
     (let [final-pitches
           (reduce (fn [final-pitches v]
                     (conj final-pitches
                           (add-voice-against-all scale pitch-range rules (:tolerance (nth specs v))
                                                   final-pitches (nth voice-sub-pitches v))))
                   [(first voice-sub-pitches)]
                   (range 1 (count voice-specs)))]
       (mapv (fn [pitches durs] (mapv vector pitches durs)) final-pitches voice-sub-durs)))))

(comment
  ;; NOTE: the reference's own demo_3_voices/demo_4_voices both throw
  ;; (confirmed live) -- they mix a [1 1 1 1 1 1 1 1] density-envelope
  ;; voice with a [2 2 2 2 2 2 2 2] one, violating the engine's own
  ;; "every voice must produce the same number of sub-notes" contract.
  ;; This example uses matching envelopes throughout instead.
  (generate [60 62 64 65 67 69 71 72] [55 84]
            [0.5 0.5 1.0 0.5 0.5 1.0 0.5 0.5]
            [{:motif {:steps [0 2 -1 2] :durations [0.5 0.5 0.5 0.5]}
              :delay-beats 0.0 :strictness 0.8 :transpose 0
              :density-envelope (vec (repeat 8 1)) :tolerance 2}
             {:motif {:steps [0 2 -1 2] :durations [0.5 0.5 0.5 0.5]}
              :delay-beats 1.0 :strictness 0.8 :transpose 4
              :density-envelope (vec (repeat 8 1)) :tolerance 3
              :rhythm-params {:randomize? true :long-first-bias 0.5}}])
  )
