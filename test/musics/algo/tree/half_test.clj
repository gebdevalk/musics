(ns ^:algo musics.algo.tree.half-test
  (:require [clojure.test :refer [deftest is testing]]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.algo.tree.half :as half]
            [musics.algo.random.core :as rc]
            [musics.algo.pitch :as pitch]
            [musics.algo.duration :as duration]
            [musics.algo.volume :as volume]
            [musics.domain :as d]))

(defn- on-shelf [shelf] (set (for [[k v] (t/algos) :when (= shelf (:shelf v))] k)))

(deftest every-raw-source-is-offered-as-the-half-products-its-bridges-make
  (is (every? (on-shelf "pitch") [:pitch/lorenz :pitch/walk :pitch/walk-degrees :pitch/henon]))
  (is (every? (on-shelf "duration") [:duration/euclid :duration/poisson :duration/walk :duration/lorenz]))
  (is (every? (on-shelf "volume") [:volume/walk :volume/lorenz :volume/tala]))
  (is ((on-shelf "articulation") :articulation/tala))
  (is ((on-shelf "volume") :volume/euclid) "a rhythm's accents as volumes")
  (is ((on-shelf "articulation") :articulation/euclid) "and as articulations")
  (testing "raw material stays in the workshop"
    (is (every? (on-shelf "workshop") [:euclid :lorenz :walk :pulses->durations :threshold :tilt])))
  (testing "an algo that delivers a half product itself sits on that shelf too"
    (is (every? (on-shelf "pitch") [:scale :int-range :constrained]))))

(deftest a-specialised-algo-is-its-source-then-its-bridge
  (let [explicit (lib/points->pitches (lib/lorenz))
        params   {:len 40 :dt 0.02}]
    (is (= (t/run explicit (assoc params :from-lo -20.0 :from-hi 20.0))
           (t/run (pitch/lorenz) (assoc params :pitch-from-lo -20.0 :pitch-from-hi 20.0)))))
  (is (= '(zip (duration/euclid) (pitch/walk-degrees)) (t/show (lib/zip (duration/euclid) (pitch/walk-degrees)))))
  (is (= [1/4 1/4 1/2] (t/run (duration/euclid) {:k 3 :n 4 :duration-pulse 1/4}))))

(deftest bridge-params-carry-the-half-product
  (let [ks (set (map :key (t/param-keys (pitch/walk))))]
    (is (every? ks [:start :len :pitch-lo :pitch-hi :pitch-from-lo :pitch-from-hi]))
    (is (not-any? ks [:lo :hi :from-lo]))))

(deftest the-input-range-defaults-to-what-the-source-gives
  (let [{lo :default} (first (filter #(= :pitch-from-lo (:key %)) (t/param-keys (pitch/walk))))
        {hi :default} (first (filter #(= :pitch-from-hi (:key %)) (t/param-keys (pitch/walk))))]
    (is (< 50 lo 60 hi 70) "walk starts at 60 and stays near it")
    (rc/seed! 11)
    (let [ps (t/run (pitch/walk) {})]
      (is (< 1 (count (distinct ps))) "the defaults already move"))))

(defn- louder-is-higher?
  "Sorted by volume, the pitches never fall."
  [notes]
  (apply <= (map (comp first :pitches) (sort-by (comp :volume :overrides) notes))))

(deftest one-as-one-run
  (let [notes (fn [as] (t/run (lib/+volume (lib/zip (lib/cycle> [1/8])
                                                    (if as (pitch/normal :as as) (pitch/normal)))
                                           (if as (volume/normal :as as) (volume/normal)))
                              {}))]
    (rc/seed! 5)
    (let [ns (notes :a)]
      (is (louder-is-higher? ns)
          "with one :as, pitch and volume come from the same draws: the higher note is the louder"))
    (rc/seed! 5)
    (let [ns (notes nil)]
      (is (not (louder-is-higher? ns)) "without, two runs"))))

(def ^:private line [(d/leaf nil nil 1/8 [60]) (d/leaf nil nil 1/4 [72]) (d/rest* nil nil 1/8) (d/leaf nil nil 1/8 [74])])

(deftest leaf-filters
  (let [high? #(and (:pitches %) (< 70 (first (:pitches %))))]
    (testing "remove> drops: the line gets shorter"
      (is (= [[60] nil] (map :pitches (t/run (lib/remove> line) {:fn high?})))))
    (testing "mute> silences: each becomes a rest of its duration, the rhythm stays"
      (let [out (t/run (lib/mute> line) {:fn high?})]
        (is (= (map :duration line) (map :duration out)))
        (is (= [false true true true] (map d/rest? out)))))
    (testing "filter> keeps"
      (is (= [[72] [74]] (map :pitches (t/run (lib/filter> line) {:fn high?})))))
    (testing "on a half product"
      (is (= [60 64] (t/run (lib/filter> [60 72 64 79]) {:fn #(< % 70)}))))))

(deftest pulse-algos-vary-durations
  (is (= #{:duration/emi :duration/mutate :duration/oblique :duration/rnn :duration/tuplets
           :duration/necklace :duration/bracelet :duration/crossover :duration/tiling}
         (set (for [[k v] (t/algos) :when (and (= "duration" (namespace k)) (seq (:in v)))] k))))
  (testing "durations become a pulse grid and back"
    (is (= [0 0 1 0 0 0 1] (vec (half/durations->pulses [(d/rest* nil nil 1/8) 1/4 1/16] 1/16))))
    (is (= [1/8 1/4 3/8] (vec (t/run (lib/pulses->durations (vec (half/durations->pulses [1/8 1/4 3/8] 1/16))) {})))))
  (let [ds [1/8 1/8 1/4 3/8 1/8]]
    (testing "a rotation keeps the durations and their sum"
      (let [r (t/run (duration/necklace ds) {:index 2})]
        (is (= (sort ds) (sort r)))
        (is (= (reduce + ds) (reduce + r)))))
    (testing "two rhythms crossed"
      (is (= [1/8 1/8 1/4 1/4 1/4] (t/run (duration/crossover ds [1/4 1/4 1/4 1/4]) {:crossover-point 4}))))
    (testing "the grid is the resolution: a duration rounds to whole pulses"
      (is (= [1/8 1/4] (t/run (duration/necklace [1/16 3/16]) {:index 0 :duration-pulse 1/8}))))
    (testing "on the shop floor, after a specialised source"
      (is (seq (t/run (lib/zip (duration/emi (duration/euclid)) (lib/cycle> lib/scale)) {:k 5 :n 16}))))))
