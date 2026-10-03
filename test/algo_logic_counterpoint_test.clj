(ns ^:algo algo-logic-counterpoint-test
  (:require [clojure.test :refer [deftest is testing]]
            [algo.logic.counterpoint :as cp]
            [algo.logic.counterpoint.intervals :as iv]
            [algo.tree :as t]
            [algo.tree.lib :as lib]
            [musics.core :as m]))

(def dorian [62 65 64 62 67 65 69 67 65 64 62])   ; D F E D G F A G F E D
(def cf4 [[62 1] [65 1] [64 1] [62 1]])            ; D F E D, for hand examples

(defn- rules [voices parts] (set (map :rule (cp/check {:voices voices :parts parts}))))
(def above [[:upper 1] [:cantus :cantus]])
(defn- above-k [k] [[:upper k] [:cantus :cantus]])

;; ---------------------------------------------------------------------------
;; Intervals and modes
;; ---------------------------------------------------------------------------

(deftest intervals-know-their-spelling
  (let [n #(iv/note (iv/mode-key 62 :dorian) %)]
    (is (= :P5 (iv/quality (n 62) (n 69))))
    (is (= :A4 (iv/quality (n 65) (n 71))) "F-B: an augmented fourth")
    (is (= :d5 (iv/quality (n 71) (n 77))) "B-F: a diminished fifth")
    (is (= {:steps 11 :semis 19 :octaves 1 :quality :P5} (iv/interval (n 50) (n 69))) "a twelfth")
    (is (iv/melodic? (n 69) (n 62)) "a fifth down")
    (is (not (iv/melodic? (n 62) (n 71))) "no major sixth")
    (is (iv/melodic? (n 64) (n 72)) "the minor sixth upwards")
    (is (not (iv/melodic? (n 72) (n 64))) "but not downwards")))

(deftest modes-and-ficta
  (is (= :dorian (iv/infer-mode dorian)))
  (is (= :phrygian (iv/infer-mode [64 60 62 60 57 69 67 64 65 64])))
  (let [cs (iv/candidates 62 :dorian [60 74])
        ficta (into {} (map (juxt :m :ficta) (filter :ficta cs)))]
    (is (= {61 :cadence 73 :cadence 66 :final 70 :free} ficta)
        "D dorian: C# at the cadence, F# in the final chord, B-flat anywhere")
    (is (= 28 (:d (first (filter #(= 61 (:m %)) cs)))) "C# keeps its letter: a C"))
  (is (empty? (filter :ficta (iv/candidates 64 :phrygian [60 72]))) "phrygian: no raised leading tone"))

;; ---------------------------------------------------------------------------
;; The rules, both ways
;; ---------------------------------------------------------------------------

(deftest first-species-rules
  (is (empty? (rules [[[69 1] [69 1] [73 1] [74 1]] cf4] above)) "a correct line")
  (is (contains? (rules [[[69 1] [72 1] [71 1] [74 1]] cf4] above) :parallel-perfects))
  (is (contains? (rules [[[74 1] [81 1] [76 1] [74 1]] cf4] above) :hidden-perfects)
      "similar motion into the octave, in two voices")
  (is (contains? (rules [[[74 1] [76 1] [73 1] [74 1]] cf4] above) :dissonance) "a seventh")
  (is (contains? (rules [[[69 1] [69 1] [72 1] [74 1]] cf4] above) :leading-tone)
      "C natural rising to the final")
  (is (contains? (rules [cf4 [[55 1] [57 1] [61 1] [62 1]]] [[:cantus :cantus] [:lower 1]]) :first-interval)
      "below the cantus a fifth may not begin"))

(deftest third-species-figures
  (let [q 1/4
        line (fn [bar1] [(into bar1 [[74 q] [72 q] [70 q] [69 q] [67 q] [69 q] [71 q] [73 q] [74 1]]) cf4])]
    (is (empty? (rules (line [[74 q] [72 q] [71 q] [69 q]]) (above-k 3))) "passing tones")
    (is (empty? (rules (line [[74 q] [72 q] [69 q] [71 q]]) (above-k 3))) "the nota cambiata")
    (is (contains? (rules (line [[74 q] [72 q] [69 q] [67 q]]) (above-k 3)) :cambiata)
        "a cambiata must turn back up")
    (is (contains? (rules (line [[74 q] [72 q] [76 q] [74 q]]) (above-k 3)) :dissonance-left)
        "no leap from a dissonance otherwise")))

(deftest fourth-species-suspensions
  (is (empty? (rules [[[nil 1/2] [69 1] [74 1] [73 1/2] [74 1]] cf4] (above-k 4)))
      "a 7-6 suspension resolving down to the leading tone")
  (is (contains? (rules [[[nil 1/2] [69 1] [74 1] [76 1/2] [74 1]] cf4] (above-k 4)) :dissonance-left)
      "a suspension resolving up"))

(deftest cantus-warnings
  (is (empty? (cp/check-cantus dorian)))
  (is (contains? (set (map :rule (cp/check-cantus [62 65 65 62]))) :repetition))
  (is (contains? (set (map :rule (cp/check-cantus [62 69 64 69]))) :begin-on-final)))

;; ---------------------------------------------------------------------------
;; Generating
;; ---------------------------------------------------------------------------

(deftest every-voice-count-and-kind-solves-and-checks
  (doseq [nv [2 3 4] k [1 2 3 4 5]]
    (testing (str nv " voices, kind " k)
      (let [r (cp/counterpoint {:cantus dorian :voices nv :kind k})]
        (is (some? r))
        (is (empty? (cp/check r)) "the generator obeys its own checker")
        (is (= dorian (mapv first (nth (:voices r) (.indexOf (mapv second (:parts r)) :cantus))))
            "the cantus is kept as given")
        (is (every? #(= 11 (reduce + (map second %))) (:voices r)) "every voice lasts 11 bars")))))

(deftest seeds-reproduce
  (let [opts {:cantus dorian :voices 3 :kind 2 :seed 7}]
    (is (= (:voices (cp/counterpoint opts)) (:voices (cp/counterpoint opts))))))

(deftest per-voice-kinds
  (let [r (cp/counterpoint {:cantus dorian :voices 3 :kinds [2 3]})]
    (is (= [[:soprano 2] [:tenor :cantus] [:bass 3]] (:parts r)))
    (is (empty? (cp/check r)))))

(deftest as-musics-text-and-tree-algo
  (let [r (cp/counterpoint {:cantus dorian :voices 2 :kind 5})
        txt (cp/->mus r :cpt)]
    (is (re-find #"\{cpt: \[ !acc:explicit" txt))
    (with-out-str (m/parse txt))
    (is (= (reduce + (map #(count (remove (comp nil? first) %)) (:voices r)))
           (count (filter #(= :note (:kind %)) (m/events :cpt))))
        "every note parses and plays"))
  (is (= 2 (count (t/run (lib/species dorian) {:voices 2 :kind 1}))))
  (is (= [] (t/run (lib/species [60 61]) {})) "no solution: no layers"))
