(ns music-parser-test
  "Parser test. Run: lein test music-parser-test"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [input.reader.parser.music-parser :as p]
            [core.domain.music-domain :as d]))

(defn parse [text] (p/parse text))
(defn leaves [parsed] (filter d/leaf? (:tokens parsed)))
(defn rests  [parsed] (filter d/rest? (:tokens parsed)))
(defn leaf   [parsed] (first (leaves parsed)))

;; ============================================================
;; LEAVES
;; ============================================================

(deftest simple-notes
  (testing "plain notes with duration"
    (let [ls (leaves (parse "c4 d4 e4"))]
      (is (= 3 (count ls)))
      (is (= [60] (:pitches (first ls))))
      (is (= 1/4 (:duration (first ls))))
      (is (= [62] (:pitches (second ls)))))))

(deftest rests-test
  (testing "rests"
    (let [rs (rests (parse "r4 r2"))]
      (is (= 2 (count rs)))
      (is (= 1/4 (:duration (first rs))))
      (is (= 1/2 (:duration (second rs)))))))

(deftest duration-inheritance
  (testing "missing duration inherits from previous"
    (let [ls (leaves (parse "c2 d e f"))]
      (is (= 1/2 (:duration (nth ls 0))))
      (is (= 1/2 (:duration (nth ls 1))))
      (is (= 1/2 (:duration (nth ls 2))))
      (is (= 1/2 (:duration (nth ls 3)))))))

(deftest relative-pitch
  (testing "diatonic climbing"
    (let [ls (leaves (parse "c d e f g a b"))]
      (is (= [60 62 64 65 67 69 71] (mapv (comp first :pitches) ls)))))
  (testing "accidentals"
    (let [ls (leaves (parse "c eb f# g"))]
      (is (= [60 63 66 67] (mapv (comp first :pitches) ls))))))

(deftest interval-direction
  (testing "fifths go up, sixths go down"
    (let [ls (leaves (parse "c g"))]
      (is (= [60 67] (mapv (comp first :pitches) ls)) "fifth up"))
    (let [ls (leaves (parse "c a"))]
      (is (= [60 57] (mapv (comp first :pitches) ls)) "sixth down"))
    (let [ls (leaves (parse "c b"))]
      (is (= [60 59] (mapv (comp first :pitches) ls)) "seventh down, nearest"))
    (let [ls (leaves (parse "c b'"))]
      (is (= [60 71] (mapv (comp first :pitches) ls)) "seventh up with tick"))
    (let [ls (leaves (parse "c f"))]
      (is (= [60 65] (mapv (comp first :pitches) ls)) "fourth up"))))

(deftest absolute-pitch
  (testing "uppercase notes"
    (let [ls (leaves (parse "C4 D4 E4"))]
      (is (= [60 62 64] (mapv (comp first :pitches) ls)))))
  (testing "mixed absolute then relative"
    (let [ls (leaves (parse "C4 D4 c d e"))]
      (is (= [60 62 60 62 64] (mapv (comp first :pitches) ls))))))

(deftest articulations
  (testing "resolved to duration multipliers"
    (let [ls (leaves (parse "c4-. d4-^ e4-_"))]
      (is (= 0.4 (:duration (:articulation (nth ls 0)))) "staccato -> 0.4")
      (is (= 0.55 (:duration (:articulation (nth ls 1)))) "marcato -> 0.55")
      (is (= 0.8 (:duration (:articulation (nth ls 2)))) "portato -> 0.8")))
  (testing "marcato adds dynamic"
    (let [ls (leaves (parse "c4-^"))]
      (is (= 10 (:dynamic (nth ls 0))) "marcato dynamic +10")))
  (testing "chord articulation"
    (let [ls (leaves (parse "<c e g>4-."))]
      (is (= 0.4 (:duration (:articulation (first ls)))) "chord staccato"))))

(deftest ties
  (testing "tied note"
    (let [ls (leaves (parse "c4~ d4"))]
      (is (true? (:tied (first ls))))
      (is (false? (:tied (second ls)))))))

;; ============================================================
;; CHORDS
;; ============================================================

(deftest chords
  (testing "simple triad"
    (let [l (leaf (parse "<c e g>2"))]
      (is (= [60 64 67] (:pitches l)))
      (is (= 1/2 (:duration l)))))
  (testing "with articulation"
    (let [l (leaf (parse "<c e g>4-."))]
      (is (= [60 64 67] (:pitches l)))
      (is (= 1/4 (:duration l)))
      (is (= 0.4 (:duration (:articulation l))))))
  (testing "relative pitch after chord"
    (let [ls (leaves (parse "<c e g>2 a b"))]
      (is (= [60 64 67] (:pitches (first ls))))
      (is (= [69] (:pitches (nth ls 1))))
      (is (= [71] (:pitches (nth ls 2)))))))

;; ============================================================
;; INSTRUCTIONS + ASSIGNMENTS
;; ============================================================

(deftest instructions
  (testing "dynamic constants"
    (let [ts (:tokens (parse "!mf !ff !ppp"))]
      (is (= :instruction (:type (nth ts 0))))
      (is (= :mf (:const (nth ts 0))))
      (is (= :ff (:const (nth ts 1))))
      (is (= :ppp (:const (nth ts 2)))))))

(deftest assignments
  (testing "int float const assignments with : syntax"
    (let [ts (:tokens (parse "!art:80 !pan:0.0 !vol:mf"))]
      (is (= :assignment (:type (nth ts 0))))
      (is (= :art (:key (nth ts 0))))
      (is (= 80 (:val (nth ts 0))))
      (is (= :pan (:key (nth ts 1))))
      (is (= 0.0 (:val (nth ts 1))))
      (is (= :vol (:key (nth ts 2))))
      (is (= :mf (:val (nth ts 2)))))))

(deftest key-assignment
  (testing "!key:C.major sets keyScale in context"
    (let [children (d/composite-children (:score (parse "[!key:C.major c4 d4]")))]
      (is (= 1 (count children)) "one composite")
      (let [c (first children)]
        (is (d/composite? c))
        (is (some? (d/ctx-value (:context c) :key 0.0))
            "keyScale should be set"))))
  (testing "!key:F#.minor works"
    (let [children (d/composite-children (:score (parse "[!key:F#.minor c4 d4]")))]
      (is (= 1 (count children)))
      (let [ks (d/ctx-value (:context (first children)) :key 0.0)]
        (is (some? ks))
        (is (= "F#" (get-in ks [:signature :display])) "signature display should be F#")
        (is (= :minor (:name (:scale ks))) "scale should be minor"))))
  (testing "!key:C defaults to major"
    (let [children (d/composite-children (:score (parse "[!key:C c4 d4]")))]
      (is (= 1 (count children)))
      (let [ks (d/ctx-value (:context (first children)) :key 0.0)]
        (is (some? ks))
        (is (= :major (:name (:scale ks))) "defaults to major")))))

;; ============================================================
;; TOKENIZER
;; ============================================================

(deftest tokenizer
  (testing "classifies note chord rest instruction"
    (let [tokens (p/tokenize "c4 <c e g>2 r4 !mf")]
      (is (= :NOTE (:type (nth tokens 0))))
      (is (= :CHORD (:type (nth tokens 1))))
      (is (= :REST (:type (nth tokens 2))))
      (is (= :BANG_CONST (:type (nth tokens 3)))))))
(deftest all-bang-constants
  (testing "all !constants tokenize as BANG_CONST"
    (let [constants ["!silence" "!pppp" "!ppp" "!pp" "!p" "!mp" "!mf" "!f" "!ff" "!fff" "!ffff"
                     "!cresc" "!decresc" "!dim" "!sfz" "!fp"
                     "!left" "!center" "!right" "!near" "!far"
                     "!stageLeft" "!stageCenter" "!stageRight"
                     "!largo" "!lento" "!adagio" "!andante" "!moderato" "!allegro"
                     "!vivace" "!presto" "!prestissimo"
                     "!rit" "!acc" "!rubato"
                     "!straight" "!swing" "!shuffle"
                     "!jazz" "!latin" "!rock" "!classical" "!swingFeel"
                     "!DC" "!DS" "!Segno" "!Coda" "!ToCoda" "!Fine"
                     "!DC_al_Fine" "!DS_al_Coda"
                     "!repeatStart" "!repeatEnd"
                     "!pedOn" "!pedOff" "!unaCorda" "!treCorde" "!sostPed"
                     "!commonTime" "!cutTime"
                     ]
          tokens (p/tokenize (str/join " " constants))]
      (is (= (count constants) (count tokens))
          "all constants should produce tokens")
      (is (every? #(= :BANG_CONST (:type %)) tokens)
          "all should be BANG_CONST"))))


;; ============================================================
;; CONTEXT
;; ============================================================

(deftest context-chain
  (testing "score context has no parent"
    (is (nil? (:parent (:context (:score (parse "c4")))))))
  (testing "leaf has context"
    (let [l (leaf (parse "c4"))]
      (is (some? (:context l)))
      (is (nil? (:parent (:context l))) "top-level leaf context has no parent"))))

;; ============================================================
;; DRUMS
;; ============================================================

(deftest drum-resolution
  (testing "named drums via modifier"
    (let [tokens (:tokens (parse "x\\kick"))]
      (is (= 1 (count (filter d/drum? tokens))))
      (is (= 36 (:program (first (filter d/drum? tokens)))) "\\kick -> 36")))
  (testing "numeric drums via modifier"
    (let [tokens (:tokens (parse "x\\36 x4\\64"))]
      (is (= 2 (count (filter d/drum? tokens))))
      (is (= 36 (:program (first (filter d/drum? tokens)))))
      (is (= 64 (:program (second (filter d/drum? tokens)))))))
  (testing "dotted duration with modifier"
    (let [tokens (:tokens (parse "x2.\\24 x8.\\kick"))]
      (is (= 2 (count (filter d/drum? tokens))))
      (is (= 24 (:program (first (filter d/drum? tokens)))))
      (is (= 36 (:program (second (filter d/drum? tokens))))))))
