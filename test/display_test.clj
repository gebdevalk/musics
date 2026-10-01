(ns ^:engine display-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [test-support :refer [with-fresh-registries]]
            [musics.core :as m]
            [core.repo :as repo]
            [core.compose :as compose]))

;; a fresh session too, so auto ids (s1, p1, ...) don't depend on test order
(use-fixtures :each (fn [f] (with-fresh-registries (with-out-str (m/reset)) (f))))

(defn- parse! [text] (with-out-str (m/parse text)))

(defn- display [& args] (apply compose/display (repo/registry) args))

(deftest a-part-as-its-voice-plays-it
  (parse! "[t: { [c4 d4] [e4] } g4 | r8 <c e g>2 ]")
  (is (= "TAA  [t: {p1: TAA [s1: C4/4 D4/4 ]  TAB [s2: E4/4 ] } G4/4 | r8 <C5/ E5/ G5/>2 ]"
         (display :t))))

(deftest a-top-level-set-is-one-line-per-voice-lowest-first
  (parse! "[hi: c'4 ]")
  (parse! "[lo: C3/4 ]")
  (is (= "TAA :algo :x  [lo: C3/4 ]\nTAB  [hi: C5/4 ]"
         (display #{:hi [:lo :algo :x]}))))

(deftest tags-groups-and-repeats
  (parse! "[a: c4 ]")
  (parse! "[r: \\repeat volta 2 [ d4 ] \\alternative [ [ e4 ] ] ]")
  (is (= "TAA :algo :y  [ [[a: C4/4 ] :algo :x] [r: \\repeat volta 2 [s1: D4/4 ] \\alternative [ [s3: [s2: E4/4 ] ] ] ] ]"
         (display [[:a :algo :x] :r] :algo :y)))
  (is (= "TAA  [ { TAA [a: C4/4 ]  TAB :algo :x [r: \\repeat volta 2 [s1: D4/4 ] \\alternative [ [s3: [s2: E4/4 ] ] ] ] } ]"
         (display [(compose/par :a [:r :algo :x])]))))

(deftest display-shows-no-time-and-an-unknown-id-plainly
  (is (= "TAA  ?? :nope" (display :nope))))

(deftest instructions-show-as-written
  (parse! "[v: !mf !tempo:120 c4 d ]")
  (is (= "TAA  [v: !mf !tempo:120 C4/4 D4/4 ]" (display :v))))

(deftest a-bare-top-level-repeat-is-its-own-part
  (is (= [:s2] (:ids (m/parse "\\repeat unfold 2 [ c8 d ]"))))
  (is (= "TAA  [s2: \\repeat unfold 2 [s1: C4/8 D4/8 ] ]" (display :s2))))
