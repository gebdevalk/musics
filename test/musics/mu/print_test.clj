(ns ^:parsing musics.mu.print-test
  "Printing is isomorphic to reading: print a value, read the text back,
   and it prints the same -- and the musical content is equal."
  (:require [clojure.test :refer [deftest is testing]]
            [musics.domain :as d]
            [musics.input.reader.builder :as builder]
            [musics.mu.grammar :as g]
            [musics.mu.print :as p]
            [musics.mu.value :as v]))

(defn- read1 [text]
  (let [nodes (filterv v/literal? (rest (g/parse text)))]
    (first (:values (v/read-literals nodes text (:repo (builder/empty-session)) {})))))

(defn- leaves [x]
  (if (v/material? x)
    (filter #(#{:LEAF :REST :DRUM} (:type %)) (d/children (:repo x) (v/node x)))
    [x]))

(defn- content [x]
  (mapv (juxt :type :pitches :duration :articulation :dynamic :overrides :program) (leaves x)))

(deftest leaves-print-absolutely
  (is (= "C4/4" (p/show (read1 "c4"))))
  (is (= "D5/8" (p/show (read1 "D5/8"))))
  (is (= "<A3 C4 E4>4" (p/show (read1 "<a c e>4"))) "no slash after a chord pitch's octave")
  (is (= "r8" (p/show (read1 "r8")))))

(deftest primitives-print-as-clojure
  (is (= "7" (p/show 7)))
  (is (= "1/4" (p/show 1/4)))
  (is (= "0.5" (p/show 0.5)))
  (is (= ":s12" (p/show :s12)))
  (is (= "\"text\"" (p/show "text"))))

(deftest containers-keep-their-instructions-and-ids
  (is (= "[!mf C4/4 D4/4 E4/4]" (p/show (read1 "[!mf c4 d e]"))))
  (is (= "[motif: G3/4 A3/4]" (p/show (read1 "[motif: g a]"))) "a written id is kept (g: the G nearest C4)")
  (is (= "[C4/4 D4/4]" (p/show (read1 "[c d]"))) "an auto id isn't printed")
  (is (= "{[C4/4 D4/4] [E4/4 F4/4]}" (p/show (read1 "{[c d] [e f]}"))))
  (is (= "^{ loud: !ff !tempo:120 }" (p/show (read1 "^{ loud: !ff !tempo:120 }"))))
  (is (= "'[60 64 67]" (p/show (read1 "'[60 64 67]"))))
  (is (= "'[pitch C4 D4]" (p/show (read1 "'[pitch c d]"))))
  (is (= "'[/4 /8]" (p/show (read1 "'[/4 /8]")))))

(deftest round-trips
  (doseq [text ["c4" "<a c e>4" "r8" "x8\\36" "[c4 d e]" "[!mf c4 !vol< d e !ff f]" "[motif: c4\\vol:90 d-> e8. f16]"
                "{[c d] [e f]}" "[c4 | d || e]" "[\\repeat unfold 2 [c d] e]" "[\\transpose c d ( c e )]"
                "^{ !ff !tempo:4=90 !key:D.major }" "'[pitch c e g]" "'[/4 /8.]" "[!tempo:90 C5/8 d e]"]]
    (testing text
      (let [a  (read1 text)
            s  (p/show a)
            b  (read1 s)]
        (is (= s (p/show b)) (str "prints the same after reading back: " s))
        (is (= (content a) (content b)) (str "same content: " s))))))
