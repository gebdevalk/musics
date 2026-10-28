(ns ^:engine musics.compose-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [musics.test-support :refer [with-fresh-registries]]
            [musics.mu.store :as store]
            [musics.repo :as repo]
            [musics.compose :as compose]))

;; a fresh session too, so auto ids (s1, p1, ...) don't depend on test order
(use-fixtures :each (fn [f] (with-fresh-registries (with-out-str (store/reset-all!)) (f))))

(defn- parse! [text] (with-out-str (store/read! text)))

(defn- display [& args] (apply compose/display (repo/registry) args))

(deftest a-part-as-its-voice-plays-it
  (parse! "[t: { [c4 d4] [e4] } g4 | r8 <c e g>2 ]")
  (is (= "TA  [t: {p1: TA [s1: C4/4 D4/4 ]  TB [s2: E4/4 ] } G4/4 | r8 <C5 E5 G5>2 ]"
         (display :t))))

(deftest a-top-level-set-is-one-line-per-voice-lowest-first
  (parse! "[hi: c'4 ]")
  (parse! "[lo: C3/4 ]")
  (is (= "TA :algo :x  [lo: C3/4 ]\nTB  [hi: C5/4 ]"
         (display #{:hi [:lo :algo :x]}))))

(deftest tags-groups-and-repeats
  (parse! "[a: c4 ]")
  (parse! "[r: repeat 2 [ d4 ] volta [ [ e4 ] ] ]")
  (is (= "TA :algo :y  [ [[a: C4/4 ] :algo :x] [r: repeat 2 [s1: D4/4 ] volta [s3: [s2: E4/4 ] ] ] ]"
         (display [[:a :algo :x] :r] :algo :y)))
  (is (= "TA  [ { TA [a: C4/4 ]  TB :algo :x [r: repeat 2 [s1: D4/4 ] volta [s3: [s2: E4/4 ] ] ] } ]"
         (display [(compose/par :a [:r :algo :x])]))))

(deftest display-shows-no-time-and-an-unknown-id-plainly
  (is (= "TA  ?? :nope" (display :nope))))

(deftest instructions-show-as-written
  (parse! "[v: !mf !tempo:120 c4 d ]")
  (is (= "TA  [v: !mf !tempo:120 C4/4 D4/4 ]" (display :v))))

(deftest a-bare-top-level-repeat-is-its-own-part
  (let [[id] (:ids (store/read! "repeat 2 [ c8 d ]"))]
    (is (re-matches #"TA  \[s\d+: repeat 2 \[s\d+: C4/8 D4/8 \] \]" (display id)))))
