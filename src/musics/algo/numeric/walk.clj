;; Random walks, noise and tendency masks: numbers that move step by step.

(ns musics.algo.numeric.walk
  "Random walks, noise and tendencies: number streams that move step by step, drawn from musics.algo.numeric.random."
  (:refer-clojure :exclude [shuffle])
  (:require [musics.algo.numeric.scaling :as scaling]
            [musics.algo.numeric.spec :refer [num-spec pos-spec unit-spec lo hi bias clip-lo clip-hi boundary]]
            [musics.algo.numeric.random :refer [rand-double shuffle uniform weighted-coin rising falling]]))


(defn cyclic
  "A function that yields random items from coll, reshuffling after all have been used. For arpeggios or drum fills."
  {:algo {:pull {} :in [:any] :out :same}}
  [coll]
  (let [state (atom {:pool (shuffle coll) :idx 0})]
    (fn []
      (:item (swap! state
               (fn [{:keys [pool idx]}]
                 (let [[pool idx] (if (= idx (count pool))
                                     [(shuffle coll) 0]
                                     [pool idx])]
                   {:pool pool :idx (inc idx) :item (nth pool idx)})))))))


(defn walk
  "Returns a function that moves randomly by at most `step-bound` each call.
   Optional clipping keeps values in range. Good for LFOs or gradual changes."
  {:algo {:pull {} :in [] :out :number
          :params {:start      (assoc num-spec :default 60.0 :doc "first value")
                   :step-bound (assoc pos-spec :default 2.0 :doc "largest step")
                   :clip-lo    clip-lo
                   :clip-hi    clip-hi
                   :boundary   boundary}}}
  [start step-bound & {:keys [clip-lo clip-hi boundary] :or {boundary :clamp}}]
  (let [state (atom start)]
    (fn []
      (let [next (+ @state (uniform (- step-bound) step-bound))]
        (reset! state (scaling/bound boundary clip-lo clip-hi next))))))


(defn biased-walk
  "Like walk but with directional bias.
   bias > 0.5 trends upward, < 0.5 trends downward.
   Use for melodic seqs with intentional contour."
  {:algo {:pull {} :in [] :out :number
          :params {:start      (assoc num-spec :default 60.0 :doc "first value")
                   :step-bound (assoc pos-spec :default 2.0 :doc "largest step")
                   :bias       (assoc bias :doc "above 0.5 trends up")
                   :clip-lo    clip-lo
                   :clip-hi    clip-hi
                   :boundary   boundary}}}
  [start step-bound bias & {:keys [clip-lo clip-hi boundary] :or {boundary :clamp}}]
  (let [state (atom start)]
    (fn []
      (let [dir (if (< (rand-double) bias) 1 -1)
            step (* dir (uniform 0 step-bound))
            next (+ @state step)]
        (reset! state (scaling/bound boundary clip-lo clip-hi next))))))


(defn pink
  "Pink (1/f) noise between lo and hi: returns a function (Voss's dice, Dodge & Jerse 8.1) that rolls `dice` dice, the k-th again whenever bit k of a counter changes, and gives their sum scaled to [lo hi). It wanders like a walk yet stays in range: between white noise (uniform) and brown (a walk)."
  {:algo {:pull {} :in [] :out :number
          :params {:dice {:type :int :min 1 :max 16 :default 5 :doc "dice: more is slower to change"}
                   :lo   lo
                   :hi   hi}}}
  [dice lo hi]
  (let [dice  (max 1 (int dice))
        faces (atom (vec (repeatedly dice rand-double)))
        n     (atom 0)]
    (fn []
      (let [k       (swap! n inc)
            changed (bit-xor k (dec k))]
        (swap! faces (fn [fs] (reduce (fn [fs i] (if (bit-test changed i) (assoc fs i (rand-double)) fs))
                                      fs (range dice))))
        (+ lo (* (- hi lo) (/ (reduce + @faces) dice)))))))


(defn tendency
  "Koenig's tendency mask: :len values, each drawn uniformly between a
   lower and an upper bound that move in a straight seq from their
   start to their end values, so a texture narrows, widens or drifts."
  {:algo {:in [] :out :number
          :params {:len      {:type :int :min 1 :max 1024 :default 16 :doc "how many values"}
                   :lo-start (assoc num-spec :default 48.0 :doc "lower bound at the start")
                   :lo-end   (assoc num-spec :default 66.0 :doc "lower bound at the end")
                   :hi-start (assoc num-spec :default 84.0 :doc "upper bound at the start")
                   :hi-end   (assoc num-spec :default 66.0 :doc "upper bound at the end")}}}
  [len lo-start lo-end hi-start hi-end]
  (let [along (fn [a b t] (+ a (* t (- b a))))]
    (vec (for [i (range len)
               :let [t (if (> len 1) (/ i (double (dec len))) 0.0)]]
           (uniform (along lo-start lo-end t) (along hi-start hi-end t))))))


(defn glide
  "A function that moves towards a target on each call, with inertia: inertia 0 snaps to the target, 1 ignores it. For portamento or following a filter envelope."
  {:algo {:pull {:args [:target]} :in [] :out :number
          :params {:initial (assoc num-spec :default 60.0 :doc "first value")
                   :inertia (assoc unit-spec :default 0.8 :doc "0 = snaps to target, 1 = ignores it")
                   :step    (assoc pos-spec :default 0.5 :doc "random wobble")
                   :target  (assoc num-spec :default 72.0 :doc "where it glides to")}}}
  [initial inertia step]
  (let [state (atom initial)]
    (fn [target]
      (let [current @state
            next-val (+ current (* (- 1 inertia) (- target current))
                        (uniform (- step) step))]
        (reset! state next-val)
        next-val))))


(defn smooth-noise
  "A smooth continuous noise curve over [0, n-1] from n random lattice points, blended with Perlin's quintic ease (6t^5 - 15t^4 + 10t^3) so segments meet without a seam: value noise, smooth enough for parameter automation. Unlike walk and biased-walk the curve is a pure function of t: any real t in range, in any order, gives the same value; t outside clamps to the nearest end.
     ((smooth-noise 8) 3.5)        ;; the curve 3.5 steps in
     ((smooth-noise 8 20 80) 0)    ;; ranged to e.g. MIDI velocity 20-80"
  {:mu {:optional {:lo number? :hi number?}}}
  ([n] (smooth-noise n 0.0 1.0))
  ([n lo hi]
   {:pre [(pos? n)]}
   (let [lattice (vec (repeatedly n #(uniform lo hi)))]
     (if (= n 1)
       (constantly (first lattice))
       (let [max-t (double (dec n))]
         (fn [t]
           (let [t     (-> (double t) (max 0.0) (min max-t))
                 i0    (min (long t) (- n 2))
                 frac  (- t i0)
                 fade  (* frac frac frac (+ (* frac (- (* frac 6) 15)) 10))
                 a     (nth lattice i0)
                 b     (nth lattice (inc i0))]
             (+ a (* fade (- b a))))))))))


(defn generative-patch
  "Returns a function that generates musical events with rising/falling tendencies."
  []
  (let [pitch-cycler (cyclic (range 60 72))
        velocity-walk (biased-walk 80 15 0.4 :clip-lo 30 :clip-hi 127) ;; slight down bias
        rhythm-trigger #(weighted-coin 0.3)
        duration-fn #(falling 0.1 0.5 0.6)] ;; shorter durations favored

    (fn []
      (when (rhythm-trigger)
        {:pitch (pitch-cycler)
         :velocity (velocity-walk)
         :duration (duration-fn)
         :bend (* 2 (rising -1 1 0.6))}))))
