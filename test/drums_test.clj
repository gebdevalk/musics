(ns ^:algo drums-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [test-support :refer [with-fresh-session]]
            [algo.rhythmic.drums :as dr]
            [core.compose :as compose]
            [core.domain.flat-domain :as d]
            [core.events :as ev]
            [core.repo :as repo]
            [clojure.string :as str]
            [input.reader.leaf-parser :as lp]
            [musics.core :as m]))

(use-fixtures :each (fn [f] (with-fresh-session (f))))

(defn- layer-for [pattern program]
  (first (filter #(some (fn [p] (= program (:program p))) %) pattern)))

(defn- drum-onsets [pattern program]
  (let [layer (layer-for pattern program)]
    (keep identity
          (map (fn [t part] (when (d/drum? part) t))
               (reductions + 0 (map :duration layer)) layer))))

(deftest every-style-gives-parallel-layers-filling-the-bars
  (doseq [style dr/styles]
    (let [p (dr/drum-pattern style 8 0.7 0.0 1)]
      (testing style
        (is (compose/par-form? p))
        (is (every? #(= 8 (reduce + (map :duration %))) p))
        (is (every? #(every? (comp pos? :duration) %) p))
        (is (every? #(every? (some-fn d/drum? d/rest?) %) p))))))

(deftest a-seed-makes-it-reproducible
  (is (= (dr/drum-pattern :hiphop 4 0.6 0.0 7) (dr/drum-pattern :hiphop 4 0.6 0.0 7)))
  (is (not= (dr/drum-pattern :hiphop 4 0.6 0.0 7) (dr/drum-pattern :hiphop 4 0.6 0.0 8))))

(deftest jazz-ride-skips-on-the-last-triplet
  (is (= [0 1/4 5/12 1/2 3/4 11/12] (take 6 (drum-onsets (dr/drum-pattern :jazz 1 0.7 0.0 1) 51)))))

(deftest the-fill-crash-lands-on-the-next-downbeat-wrapping-to-bar-1
  (is (= [0 4] (drum-onsets (dr/drum-pattern :rock 8 0.7 0.0 1) 49))))

(deftest swing-delays-only-off-beat-16ths
  (let [hats (drum-onsets (dr/drum-pattern :funk 1 0.7 1.0 1) 42)]
    (is (= [0 3/32 1/8 7/32] (take 4 hats)))))

(deftest a-drum-hit-velocity-rides-on-the-context-volume
  (let [vel (fn [dyn] (->> (ev/events (repo/registry) [(assoc (d/drum nil nil 1/4 38) :dynamic dyn)])
                           (filter #(= :drum (:kind %))) first :velocity))]
    (is (< (vel -30.0) (vel 0) (vel 10.0)))))

(defn- parsed-layers
  "Parse `text` (one { } of [ ] layers, named :grv) and give its layers back."
  [text]
  (with-out-str (m/parse text))
  (for [id (:children (repo/current (first (:children (repo/current :grv)))))]
    (:children (repo/current id))))

(deftest drum-accents-in-text
  (is (= [[38 5] [38 10] [42 -20] [36 5] [36 nil]]
         (map (juxt :program :dynamic)
              (first (parsed-layers "[grv: { [x8\\snare-> x8\\38-^ x8\\hh\\ghost x8\\kick\\accent x8\\kick] }]"))))))

(deftest a-groove-reads-back-the-same-from-text
  (let [p    (dr/drum-pattern :funk 2 0.75 0.3 1)
        text (str "[grv: { " (str/join " " (for [l p] (str "[ " (str/join " " (map lp/part->mus l)) " ]"))) " }]")
        same (fn [layers] (map #(map (juxt :type :program :dynamic :duration) %) layers))]
    (is (= (same p) (same (parsed-layers text))))))
