(ns ^:algo musics.algo.tree.parts-test
  "Parallel material: :layer (pulse layers meant to sound together),
   :part (parallel parts of leaves) and the picks between them."
  (:require [clojure.test :refer [deftest is]]
            [musics.algo.tree :as t]
            [musics.algo.tree.lib :as lib]
            [musics.compose :as compose]
            [musics.algo.rhythmic.poly :as poly]))

(deftest each-layer-routes-into-its-own-part
  (let [voice (fn [as pitch] (lib/zip (lib/pulses->durations (lib/layer lib/polyrhythm :as as)) (lib/cycle> [pitch])))
        tree  (lib/parts (voice :low 48) (voice :high 55))
        ps    (t/run tree {:low/index 0 :high/index 1 :pulse 1/16})]
    (is (= :part (t/out-type (t/as-node tree))))
    (is (compose/par-form? ps) "plays at once")
    (is (= [3 2] (map count ps)) "3 against 2")
    (is (= [[48] [55]] (map (comp :pitches first) ps)))
    (is (= (second ps) (t/run (lib/part tree) {:index 1 :low/index 0 :high/index 1 :pulse 1/16})) "part picks one back")))

(deftest +part-adds-a-voice
  (let [ps (t/run (lib/+part (lib/parts (lib/zip [1/4] [60]) (lib/zip [1/4] [64])) (lib/zip [1/2] [67])) {})]
    (is (compose/par-form? ps))
    (is (= [[60] [64] [67]] (map (comp :pitches first) ps)))))

(deftest a-layer-is-pulses
  (is (= :pulse (t/out-type (t/as-node (lib/layer lib/hemiola)))))
  (is (every? #{0 1} (t/run (lib/layer lib/polymeter) {:index 1})))
  (is (= (mapv #(if (pos? %) 1 0) (second (poly/polymeter [[3 4] [4 4]] 12)))
         (t/run (lib/layer lib/polymeter) {:index 1}))
      "polymeter's marks, as pulses"))

(deftest alternatives-give-the-one-their-index-picks
  (is (= [0 1 0 1 1] (t/run (lib/necklace [1 0 1 0 1]) {:index 1})))
  (is (= [1 0 1 0 1] (t/run (lib/necklace [1 0 1 0 1]) {:index 0})) "the pattern itself first")
  (is (every? #(= 3 (reduce + %)) (for [i (range 7)] (t/run (lib/necklaces) {:n 8 :k 3 :index i}))))
  (is (= [1 1 0 0] (t/run (lib/crossover [1 1 1 1] [0 0 0 0]) {:crossover-point 2 :child 0}))))
