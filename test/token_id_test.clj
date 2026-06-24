(ns token-id-test
  "Tests for leaf token ID extraction via insta/span.
   Verifies that leaf.id is the original input text, not a computed value.
   Run: lein test token-id-test"
  (:require [clojure.test :refer [deftest is testing]]
            [input.reader.parser.grammar-parser :as gp]
            [core.domain.music-domain :as d]))

(defn- parse [text] (gp/parse-domain-string text))

(defn- all-leaves
  "Collect all Leaf objects from a score tree, recursively."
  [node]
  (cond
    (d/leaf? node) [node]
    (d/composite? node) (mapcat all-leaves (d/composite-children node))
    :else []))

(defn- all-rests
  "Collect all Rest objects from a score tree, recursively."
  [node]
  (cond
    (d/rest? node) [node]
    (d/composite? node) (mapcat all-rests (d/composite-children node))
    :else []))

;; ============================================================
;; Note token IDs
;; ============================================================

(deftest note-token-ids
  (testing "simple notes preserve original text as id"
    (let [ls (all-leaves (:score (parse "c4 d e")))]
      (is (= "c4" (:id (nth ls 0))))
      (is (= "d"  (:id (nth ls 1))))
      (is (= "e"  (:id (nth ls 2))))))

  (testing "notes with accidentals"
    (let [ls (all-leaves (:score (parse "c#4 eb")))]
      (is (= "c#4" (:id (first ls))))
      (is (= "eb"  (:id (second ls))))))

  (testing "notes with octave ticks"
    (let [ls (all-leaves (:score (parse "c'' d,")))]
      (is (= "c''" (:id (first ls))))
      (is (= "d,"  (:id (second ls))))))

  (testing "note with articulation includes full token"
    (let [ls (all-leaves (:score (parse "c4-.")))]
      (is (= "c4-." (:id (first ls))))))

  (testing "note with tie"
    (let [ls (all-leaves (:score (parse "c4~ c")))]
      (is (= "c4~" (:id (first ls)))))))

;; ============================================================
;; Rest token IDs
;; ============================================================

(deftest rest-token-ids
  (testing "rests preserve original text as id"
    (let [rs (all-rests (:score (parse "r4 r2")))]
      (is (= "r4" (:id (first rs))))
      (is (= "r2" (:id (second rs))))))

  (testing "bare rest without duration"
    (let [rs (all-rests (:score (parse "c4 r")))]
      (is (= "r" (:id (first rs)))))))

;; ============================================================
;; Chord token IDs
;; ============================================================

(deftest chord-token-ids
  (testing "chord preserves original text as id"
    (let [ls (all-leaves (:score (parse "<c e g>4")))]
      (is (= "<c e g>4" (:id (first ls))))))

  (testing "chord without duration"
    (let [ls (all-leaves (:score (parse "c4 <c e g>")))]
      (is (= "<c e g>" (:id (second ls)))))))

;; ============================================================
;; Inside composites
;; ============================================================

(deftest token-ids-in-sequence
  (testing "notes inside a sequence have correct token ids"
    (let [ls (all-leaves (:score (parse "{c4 d e f}")))]
      (is (= 4 (count ls)))
      (is (= "c4" (:id (nth ls 0))))
      (is (= "d"  (:id (nth ls 1))))
      (is (= "e"  (:id (nth ls 2))))
      (is (= "f"  (:id (nth ls 3)))))))

(deftest token-ids-in-parallel
  (testing "notes inside parallel sequences have correct token ids"
    (let [ls (all-leaves (:score (parse "<< {c4 d} {e f} >>")))]
      (is (= 4 (count ls)))
      (is (= "c4" (:id (nth ls 0))))
      (is (= "d"  (:id (nth ls 1))))
      (is (= "e"  (:id (nth ls 2))))
      (is (= "f"  (:id (nth ls 3)))))))

(deftest token-ids-with-instructions
  (testing "token ids correct when interleaved with instructions"
    (let [ls (all-leaves (:score (parse "!mf c4 d !ff e")))]
      (is (= 3 (count ls)))
      (is (= "c4" (:id (nth ls 0))))
      (is (= "d"  (:id (nth ls 1))))
      (is (= "e"  (:id (nth ls 2)))))))
