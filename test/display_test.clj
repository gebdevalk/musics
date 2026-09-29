(ns ^:engine display-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [test-support :refer [with-fresh-session]]
            [musics.core :as m]
            [core.repo :as repo]
            [core.compose :as compose]))

(use-fixtures :each (fn [f] (with-fresh-session (f))))

(defn- parse! [text] (with-out-str (m/parse text)))

(defn- display [& args] (apply compose/display (repo/registry) args))

(deftest a-part-as-its-voice-plays-it
  (parse! "[t: { [c4 d4] [e4] } g4 | r8 <c e g>2 ]")
  (is (= "TAA  [t: {p1: TAA [s1: C4/4 D4/4 ]  TAB [s2: E4/4 ] } G4/4 | r8 <C5/ E5/ G5/>2 ]"
         (display :t))))

(deftest a-top-level-par-is-one-line-per-voice-lowest-first
  (parse! "[hi: c'4 ]")
  (parse! "[lo: C3/4 ]")
  (is (= "TAA :algo :x  [lo: C3/4 ]\nTAB  [hi: C5/4 ]"
         (display (compose/par :hi [:lo :algo :x])))))

(deftest tags-groups-and-repeats
  (parse! "[a: c4 ]")
  (parse! "[r: \\repeat volta 2 [ d4 ] \\alternative [ [ e4 ] ] ]")
  (is (= "TAA :algo :y  [ [[a: C4/4 ] :algo :x] [r: \\repeat volta 2 [s3: D4/4 ] \\alternative [ [s5: [s4: E4/4 ] ] ] ] ]"
         (display [[:a :algo :x] :r] :algo :y)))
  (is (= "TAA  [ { TAA [a: C4/4 ]  TAB :algo :x [r: \\repeat volta 2 [s3: D4/4 ] \\alternative [ [s5: [s4: E4/4 ] ] ] ] } ]"
         (display [(compose/par :a [:r :algo :x])]))))

(deftest display-shows-no-time-and-an-unknown-id-plainly
  (is (= "TAA  ?? :nope" (display :nope))))
