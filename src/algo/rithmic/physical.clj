;; physical.clj
;; Clojure port of pymusics src/algorithm/advanced_rhythm.py sections
;; 10 and 12 -- rhythms derived from simulated physical/natural
;; processes (pendulum swing, bouncing ball, the logistic map as a
;; chaotic rhythm source, heartbeat, rainfall, birdsong). Random draws
;; go through algo.random, same as algo.rithmic.stochastic.

(ns algo.rithmic.physical
  (:require [algo.random :as rand]))

(defn pendulum-rhythm
  "Onset timings (seconds) of a simplified pendulum's downward
   zero-crossings -- theta(t) = initial-angle * cos(omega*t), omega =
   sqrt(gravity/length) -- sampled at sample-rate steps/second across
   duration. Only downward crossings are counted (once per full
   period), not every crossing."
  ([initial-angle length gravity duration] (pendulum-rhythm initial-angle length gravity duration 100.0))
  ([initial-angle length gravity duration sample-rate]
   (let [omega (Math/sqrt (/ gravity length))
         dt (/ 1.0 sample-rate)
         theta (fn [t] (* initial-angle (Math/cos (* omega t))))]
     (loop [t 0.0 prev-angle (theta 0.0) timings []]
       (if (>= t duration)
         timings
         (let [t' (+ t dt)
               angle (theta t')]
           (recur t' angle
                  (if (and (<= (* prev-angle angle) 0) (>= prev-angle 0))
                    (conj timings t')
                    timings))))))))

(defn bouncing-ball-rhythm
  "Onset timings (seconds) of a bouncing ball's impacts: dropped from
   initial-height, each bounce scaling its rebound velocity by
   restitution (0.0-1.0), under gravity, until duration is reached or
   the bounce height decays below 0.001."
  [initial-height restitution gravity duration]
  (loop [timings [0.0] height initial-height time 0.0]
    (let [fall-time (if (pos? height) (Math/sqrt (/ (* 2 height) gravity)) 0)
          time' (+ time fall-time)]
      (if (> time' duration)
        timings
        (let [impact-velocity (Math/sqrt (* 2 gravity height))
              velocity (* impact-velocity restitution)
              height' (/ (* velocity velocity) (* 2 gravity))]
          (if (< height' 0.001)
            (conj timings time')
            (recur (conj timings time') height' time')))))))

(defn logistic-map-rhythm
  "Binary pattern of the given length from the logistic map (x_{n+1} = r
   * x_n * (1 - x_n)), thresholded to 1/0 each step. r in [3.57, 4.0]
   gives a chaotic (aperiodic) sequence -- see algo.random.logistic for
   the same map as a live, stateful generator instead of a fixed-length
   pattern."
  ([r x0 length] (logistic-map-rhythm r x0 length 0.5))
  ([r x0 length threshold]
   (loop [x x0 i 0 pattern []]
     (if (= i length)
       pattern
       (let [x' (* r x (- 1 x))]
         (recur x' (inc i) (conj pattern (if (> x' threshold) 1 0))))))))

(defn heartbeat-rhythm
  "Onset timings (seconds) of a heartbeat: a base inter-beat interval
   (60/bpm) jittered by +-variability (respiratory sinus arrhythmia)
   and a slow sinusoidal respiratory-cycle modulation, across duration."
  ([] (heartbeat-rhythm 72 0.1 10.0))
  ([bpm variability duration]
   (let [base-interval (/ 60.0 bpm)]
     (loop [t 0.0 timings []]
       (if (>= t duration)
         timings
         (let [variability-factor (+ 1.0 (rand/uniform (- variability) variability))
               respiratory (* (Math/sin (* t 0.2)) 0.05)]
           (recur (+ t (* base-interval variability-factor (+ 1 respiratory)))
                  (conj timings t))))))))

(defn rainfall-rhythm
  "Onset timings (seconds) of raindrop impacts: a Poisson process at a
   rate proportional to intensity (0.0-1.0), plus (if intensity > 0.7) a
   single burst of 3-8 closely-spaced drops somewhere in the middle
   third of duration."
  ([] (rainfall-rhythm 0.5 10.0))
  ([intensity duration] (rainfall-rhythm intensity duration 100.0))
  ([intensity duration _sample-rate]
   (let [drop-rate (* intensity 20.0)
         steady (loop [t 0.0 timings []]
                  (let [wait (rand/exponential (/ 1.0 drop-rate))
                        t' (+ t wait)]
                    (if (< t' duration)
                      (recur t' (conj timings t'))
                      timings)))
         burst (when (> intensity 0.7)
                 (let [burst-time (rand/uniform (* duration 0.3) (* duration 0.7))
                       burst-drops (rand/int-range 3 9)
                       burst-interval 0.05]
                   (->> (range burst-drops)
                        (map #(+ burst-time (* % burst-interval)))
                        (filter #(< % duration)))))]
     (vec (sort (concat steady burst))))))

(def ^:private bird-song-params
  {"sparrow"    {:syllables-per-phrase [3 4 3]    :syllable-duration 0.1  :gap-duration 0.05 :phrase-gap 0.3}
   "robin"      {:syllables-per-phrase [2 2 3 2]  :syllable-duration 0.15 :gap-duration 0.08 :phrase-gap 0.5}
   "blackbird"  {:syllables-per-phrase [4 3 5 4]  :syllable-duration 0.08 :gap-duration 0.03 :phrase-gap 0.4}
   "woodpecker" {:syllables-per-phrase [8 6 10]   :syllable-duration 0.05 :gap-duration 0.02 :phrase-gap 1.0}})

(defn bird-song-rhythm
  "Onset timings (seconds) of a stylized birdsong's syllables, species-
   specific phrase/syllable/gap structure repeated until duration is
   covered. species defaults to \"sparrow\"; unrecognized species also
   fall back to \"sparrow\"'s own parameters."
  ([] (bird-song-rhythm "sparrow" 5.0))
  ([species duration]
   (let [{:keys [syllables-per-phrase syllable-duration gap-duration phrase-gap]}
         (get bird-song-params species (get bird-song-params "sparrow"))]
     (loop [t 0.0 timings []]
       (if (>= t duration)
         timings
         (let [[t' timings' stop?]
               (reduce (fn [[t timings _] phrase-length]
                         (let [[t' timings'] (reduce (fn [[t timings] _]
                                                        [(+ t syllable-duration gap-duration) (conj timings t)])
                                                      [t timings] (range phrase-length))
                               t'' (+ t' phrase-gap)]
                           (if (>= t'' duration)
                             (reduced [t'' timings' true])
                             [t'' timings' false])))
                       [t timings false] syllables-per-phrase)]
           (if stop? timings' (recur t' timings'))))))))

(comment
  (pendulum-rhythm 0.3 1.0 9.8 3.0)
  (bouncing-ball-rhythm 2.0 0.7 9.8 5.0)
  (logistic-map-rhythm 3.9 0.5 20)
  (bird-song-rhythm "sparrow" 2.0)
  )
