(ns ^:domain musics.common.music-data-test
  (:require [clojure.test :refer [deftest is are testing]]
            [musics.common.music-data :as data]
            [musics.mu.value :as v]))

(deftest smart-get-finds-what-a-start-can-only-mean
  (is (= 120 (data/smart-get {:tempo 120 :timbre 42} :te)))
  (is (= 120 (data/smart-get {:tempo 120 :timbre 42} :tempo)))
  (is (nil? (data/smart-get {:tempo 120} :x)))
  (is (thrown-with-msg? Exception #"'t' could be tempo or timbre" (data/smart-get {:tempo 120 :timbre 42} :t)))
  (testing "an exact key wins over the longer keys it begins"
    (is (= 1 (data/smart-get {:cel 1 :cello 2} :cel))))
  (testing "keys holding one value are no ambiguity"
    (is (= 49 (data/smart-get {"crash" 49 "crashcymbal" 49} "cra")))))

(deftest instruments-by-name-short-name-or-start
  (are [x p] (= p (data/program-of x))
    "violin" 40  "vln" 40  "acousticBass" 32  "acbs" 32  :bird-tweet 123
    "Bird Tweet" 123  "birdtweet" 123  "trump" 56  "pizz" 45  "glock" 9
    "vib" 11  "cel" 8)
  (is (nil? (data/program-of "zzz")))
  (is (thrown-with-msg? Exception #"'viol' could be viola or violin" (data/program-of "viol")))
  (is (thrown-with-msg? Exception #"acousticBass, acousticGrand, acousticGuitarNylon or acousticGuitarSteel"
                        (data/program-of "acou"))))

(deftest drums-and-kits-likewise
  (are [x k] (= k (data/resolve-drum x))
    "kick" 36  "36" 36  "cow" 56  "closed" 42  "tamb" 54  "Acoustic Snare" 38  "vib" 58)
  (is (thrown-with-msg? Exception #"Crash Cymbal 1 or Crash Cymbal 2" (data/resolve-drum "cr")))
  (is (nil? (data/resolve-drum "zz")))
  (is (= 24 (data/kit-of "elec")))
  (is (thrown-with-msg? Exception #"sfx or standard" (data/kit-of "s"))))

(deftest in-musics-text
  (is (thrown-with-msg? Exception #"'viol' could be viola or violin" (v/read-domain "[!i:viol c]")))
  (is (thrown-with-msg? Exception #"'nope' is not a drum" (v/read-domain "[x8\\nope]")))
  (is (= [56 42] (->> (v/read-domain "[x8\\cow x\\closed]") :tree vals (mapcat :children) (keep :program)))))

(deftest the-matches-listed
  (is (= [{:program 40 :name "violin" :short "vln"} {:program 41 :name "viola" :short "vla"}] (data/instrument-matches "viol")))
  (is (= [{:program 8 :name "celesta" :short "cel"}] (data/instrument-matches "cel")) "an exact name alone")
  (is (= [42] (map :key (data/drum-matches "42"))))
  (is (= ["hat" "hh" "hihat"] (:also (first (data/drum-matches "closed")))))
  (is (= ["standard" "sfx"] (map :name (data/kit-matches "s")))))
