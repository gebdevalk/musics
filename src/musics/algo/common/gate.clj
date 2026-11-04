(ns musics.algo.common.gate
  "One general engine (gate) plus criterion constructors (pitch range, pitch class, interval, probability) that decide which notes pass.

   gate judges a Leaf by its first pitch (a chord is kept or replaced as one unit), and what becomes of a rejected part is a choice: :remove, :rest, :hold or a custom fn."
  (:require [musics.domain :as d]
            [musics.algo.numeric.random :as rand]))

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
  "Pass each Leaf of parts through select-fn; on-reject says what becomes of a rejected one. Non-Leaf parts (Rest, Drum, container, Bar, :assignment) pass untouched.
   select-fn: (part raw-prev) -> boolean. raw-prev is the Leaf just before in the original sequence, kept or not (nil at the start).
   on-reject: :remove (drop it), :rest (a Rest of the same duration), :hold (a tied Leaf on the pitch last heard in the output, a Rest if none has sounded), or a custom (part last-sounding) -> replacement-or-nil fn."
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

;; ============================================================
;; Criterion constructors -- each takes its own params and returns a
;; select-fn, (part raw-prev) -> boolean.
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
  "Keep each note with probability p, independently of pitch and of raw-prev. Draws from musics.algo.numeric.random, so a seed repeats it."
  [p]
  (fn [_part _raw-prev] (< (rand/rand-double) p)))
