(ns leaf-parser-test
  "Leaf-parser tests. Run: lein test leaf-parser-test"
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.leaf-parser :as leaf]))

;; ============================================================
;; Pitch parsing
;; ============================================================

(deftest parse-pitch-test
  (testing "absolute pitch"
    (is (= ["C4" "" ""] (leaf/parse-pitch "C4"))))
  (testing "relative pitch"
    (is (= ["c" "" ""] (leaf/parse-pitch "c")))
    (is (= ["e" "b" ""] (leaf/parse-pitch "eb")))
    (is (= ["f" "#" "''"] (leaf/parse-pitch "f#''"))))
  (testing "natural sign"
    (is (= ["d" "n" ""] (leaf/parse-pitch "dn")))))

(deftest parse-pitches-test
  (testing "chord content"
    (is (= [["c" "" ""] ["e" "" ""] ["g" "" ""]]
           (leaf/parse-pitches "<c e g>")))))

;; ============================================================
;; Pitch -> MIDI resolution
;; ============================================================

(deftest relative-pitch-midi
  (testing "diatonic climbing"
    (let [res (leaf/resolve-pitches-seq
               [["c" "" ""] ["d" "" ""] ["e" "" ""] ["f" "" ""]
                ["g" "" ""] ["a" "" ""] ["b" "" ""]]
               60)]
      (is (= [60 62 64 65 67 69 71] (first res)))))
  (testing "accidentals"
    (let [res (leaf/resolve-pitches-seq
               [["c" "" ""] ["e" "b" ""] ["f" "#" ""] ["g" "" ""]]
               60)]
      (is (= [60 63 66 67] (first res))))))

(deftest interval-direction
  (testing "fifths go up, sixths go down"
    (let [res (leaf/resolve-pitches-seq [["c" "" ""] ["g" "" ""]] 60)]
      (is (= [60 67] (first res)) "fifth up"))
    (let [res (leaf/resolve-pitches-seq [["c" "" ""] ["a" "" ""]] 60)]
      (is (= [60 57] (first res)) "sixth down"))
    (let [res (leaf/resolve-pitches-seq [["c" "" ""] ["b" "" ""]] 60)]
      (is (= [60 59] (first res)) "seventh down"))
    (let [res (leaf/resolve-pitches-seq [["c" "" ""] ["f" "" ""]] 60)]
      (is (= [60 65] (first res)) "fourth up"))))

(deftest absolute-pitch-midi
  (testing "uppercase notes"
    (let [[midis] (leaf/resolve-pitches-seq
                   [["C4" "" ""] ["D4" "" ""] ["E4" "" ""]]
                   60)]
      (is (= [60 62 64] midis))))
  (testing "mixed absolute then relative"
    (let [[midis] (leaf/resolve-pitches-seq
                   [["C4" "" ""] ["D4" "" ""] ["c" "" ""] ["d" "" ""] ["e" "" ""]]
                   60)]
      (is (= [60 62 60 62 64] midis)))))

;; ============================================================
;; Articulation resolution
;; ============================================================

(deftest resolve-articulation-by-shorthand
  (testing "staccatissimo"
    (is (= 0.25 (:duration (leaf/resolve-articulation "-!")))))
  (testing "staccato"
    (is (= 0.4 (:duration (leaf/resolve-articulation "-."))))
    (is (= 0 (:dynamic (leaf/resolve-articulation "-.")))))
  (testing "stopped"
    (is (= 0.3 (:duration (leaf/resolve-articulation "-+")))))
  (testing "marcato"
    (is (= 0.55 (:duration (leaf/resolve-articulation "-^"))))
    (is (= 10 (:dynamic (leaf/resolve-articulation "-^")))))
  (testing "portato"
    (is (= 0.8 (:duration (leaf/resolve-articulation "-_"))))))

(deftest resolve-articulation-by-name
  (testing "staccato by name"
    (is (= 0.4 (:duration (leaf/resolve-articulation "staccato")))))
  (testing "marcato by name"
    (is (= 0.55 (:duration (leaf/resolve-articulation "marcato"))))
    (is (= 10 (:dynamic (leaf/resolve-articulation "marcato")))))
  (testing "legato by name"
    (is (= 1.0 (:duration (leaf/resolve-articulation "legato")))))
  (testing "sfz by name"
    (is (nil? (:duration (leaf/resolve-articulation "sfz"))))
    (is (= 10 (:dynamic (leaf/resolve-articulation "sfz")))))
  (testing "fermata by name"
    (is (nil? (:duration (leaf/resolve-articulation "fermata"))))
    (is (= 0 (:dynamic (leaf/resolve-articulation "fermata")))))
  (testing "unknown returns as-is"
    (is (= "foo" (leaf/resolve-articulation "foo")))
    (is (nil? (leaf/resolve-articulation nil))))
  (testing "case insensitive"
    (is (= 0.55 (:duration (leaf/resolve-articulation "MARCATO"))))))
