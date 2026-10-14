(ns musics.algo.tree.half
  "Algos as the half products they deliver (doc/algo-space.md, \"Algos
   deliver half products\").

   Leaves, lines and parallels are products; pitch, duration, volume and
   articulation half products; numbers, pulses, onsets, points and
   weights raw material. A source that makes raw material is offered as
   each half product a bridge (musics.algo.bridge) makes of it: a
   specialised algo, derived here, never written by hand.

     pitch/lorenz          lorenz, its points onto the steps of a key
     duration/euclid       euclid, each onset lasting until the next
     pitch/walk            walk, each number's place in a range
     pitch/walk-degrees    walk, each number a scale step

   A specialised algo is named by its half product (its namespace:
   musics.algo.pitch, .duration, .volume, .articulation) and its source;
   when a raw type has a second bridge to the same half product, that
   one adds the bridge's input word (`-degrees`). Its params are the
   source's, then the bridge's, each prefixed with the half product
   (:pitch-lo, :duration-pulse), so two half products of one source never
   share a bridge setting. A bridge's input range (:pitch-from-lo ..
   :pitch-from-hi) defaults to what the source gives with its own
   defaults, so the defaults already sound.

   Specialised algos with the same :as and the same source read one run
   of it (musics.algo.tree/run): (pitch/lorenz :as :a) and (volume/lorenz
   :as :a) play the same attractor, x as pitch and y as volume.

   An algo that varies, elaborates or continues pulses (emi, mutate,
   oblique, rnn, tuplets, necklace, crossover, ...) is offered on
   durations too: duration/emi takes durations, lays them on a grid of
   :duration-pulse (durations->pulses), runs emi, and reads the result
   back as durations (pulses->durations) -- a rhythm varied on the
   shop floor, without leaving durations."
  (:require [clojure.string :as str]
            [musics.algo.random.core :as rc]
            [musics.domain :as d]
            [musics.algo.tree :as t]
            [musics.algo.tree.registry :as reg]))

(defn- bridges
  "Every bridge from a raw type to a half product: [[raw half short entry]]."
  [algos]
  (for [[short e] algos
        :let [[raw] (:in e) half (:out e)]
        :when (and (= "bridge" (:category e)) (= 1 (count (:in e)))
                   (reg/raw-types raw) (reg/half-products half))]
    [raw half short e]))

(defn- in-word [bridge] (first (str/split (name bridge) #"->")))

(defn- specialised-name
  "pitch/walk, or pitch/walk-degrees for a bridge whose input isn't the
   raw type itself."
  [half source raw bridge]
  (let [w (in-word bridge)]
    (keyword (name half) (str (name source) (when-not (= w (str (name raw) "s")) (str "-" w))))))

(defn- nice-bounds
  "lo..hi widened to round numbers at a tenth of the span's magnitude."
  [lo hi]
  (let [e     (long (dec (Math/floor (Math/log10 (- hi lo)))))
        k     (Math/pow 10 (abs e))
        round (fn [f x] (if (neg? e) (/ (f (* x k)) k) (* (f (/ x k)) k)))]
    [(round #(Math/floor %) lo) (round #(Math/ceil %) hi)]))

(defn- probe
  "The range of what `source` gives with its own defaults (at most 512
   values, seeded), or nil: a required param, a throw, or a flat run."
  [source]
  (let [ps (:params source)]
    (when-not (some (comp t/nan? :default) ps)
      (try
        (let [xs (->> (rc/with-seed 2026 (vec (take 512 (reg/call source [] (mapv :default ps)))))
                      flatten (filter number?))]
          (when (seq xs)
            (let [lo (apply min xs) hi (apply max xs)]
              (when (< lo hi) (nice-bounds lo hi)))))
        (catch Exception _ nil)))))

(defn- bridge-param
  "The bridge's param `p`, prefixed with the half product; an input range
   defaults to the source's own."
  [half range p]
  (let [k (keyword (str (name half) "-" (name (:name p))))]
    (cond-> (assoc p :name k)
      (and range (= :from-lo (:name p))) (assoc :default (first range))
      (and range (= :from-hi (:name p))) (assoc :default (second range)))))

(defn specialise
  "The registry entry of `source` delivered as `half` by `bridge`."
  [[source-short source] [raw half bridge-short bridge] range]
  (let [short (specialised-name half source-short raw bridge-short)]
    {:short    short
     :full     (symbol (str "musics.algo." (name half)) (name short))
     :category (name half)
     :works    (:works bridge)
     :doc      (str (:doc source) " As " (name half) ", by " (name bridge-short) ".")
     :in       []
     :out      half
     :children []
     :args     []
     :params   (into (vec (:params source)) (map #(bridge-param half range %)) (:params bridge))
     :via      {:source source-short :bridge bridge-short :split (count (:params source))}}))

(defn durations->pulses
  "Durations as pulses of `pulse` each, lazily: a note is an onset and
   then holds, a Rest only holds; each rounds to whole pulses, at least
   one. pulses->durations reads it back."
  [durs pulse]
  (mapcat (fn [x]
            (let [r? (d/rest? x)
                  n  (max 1 (Math/round (double (/ (if r? (:duration x) x) pulse))))]
              (if r? (repeat n 0) (cons 1 (repeat (dec n) 0)))))
          durs))

(defn- varies-pulses?
  "An algo that takes pulses and gives pulses with an idea of its own."
  [e]
  (and (seq (:in e)) (every? #{:pulse} (:in e)) (= :pulse (:out e))
       (not (#{"bridge" "tool"} (:category e))) (not (:via e))))

(defn lift
  "The registry entry of pulse algo `source` on durations: its children
   laid on a grid of :duration-pulse, its result read back by `bridge`
   (pulses->durations)."
  [[source-short source] bridge-short bridge]
  {:short    (keyword "duration" (name source-short))
   :full     (symbol "musics.algo.duration" (name source-short))
   :category "duration"
   :works    :whole
   :doc      (str (:doc source) " On durations, laid on a grid of :duration-pulse.")
   :in       (mapv (constantly :duration) (:in source))
   :out      :duration
   :children (:children source)
   :args     []
   :params   (into (vec (:params source)) (map #(bridge-param :duration nil %)) (:params bridge))
   :via      {:source source-short :bridge bridge-short :split (count (:params source))
              :lift   (fn [durs [pulse]] (vec (durations->pulses durs pulse)))}})

(defn specialised
  "Every specialised algo the registry's sources and bridges give."
  []
  (let [algos (into {} (map (fn [[k _]] [k (reg/algo k)])) (reg/algos))
        bs    (bridges algos)]
    (concat
     (for [[_ e :as src] algos
           :when (and (empty? (:in e)) (reg/raw-types (:out e)) (not (:via e)))
           :let [range (delay (probe e))]
           [raw half bridge-short bridge] bs
           :when (= raw (:out e))]
       (specialise src [raw half bridge-short bridge]
                   (when (some #(= :from-lo (:name %)) (:params bridge)) @range)))
     (for [[_ e :as src] algos :when (varies-pulses? e)]
       (lift src :pulses->durations (get algos :pulses->durations))))))

(defn register-all!
  "Register every specialised algo. Returns their short names."
  []
  (mapv (comp :short reg/register-entry!) (specialised)))

(defmacro defhalf
  "Define a constructor in this namespace for every specialised algo
   delivering `half`, named without its namespace: in musics.algo.pitch,
   `lorenz` is pitch/lorenz."
  [half]
  `(do ~@(for [[short] (reg/algos)
               :when (= (name half) (namespace short))]
           `(def ~(symbol (name short)) (t/constructor (reg/algo ~short))))))
