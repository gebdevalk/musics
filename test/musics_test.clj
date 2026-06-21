(ns musics-test
  (:require [clojure.test :refer :all]
            [musics :as m]
            [core.domain.music-domain :as d]))

;; Reset state before each test
(use-fixtures :each
  (fn [f]
    (reset! @#'m/registry {})
    (reset! @#'m/book [])
    (f)))

;; ============================================================
;; Parse & registry
;; ============================================================

(deftest parse-returns-id
  (let [id (m/parse "{verse: c4 d4 e4}")]
    (is (keyword? id) "parse returns a keyword")
    (is (= :verse id) "returns correct id")
    (is (d/composite? (m/find id)) "id resolves to a composite in registry")))

(deftest parse-registers-ids
  (m/parse "{verse: c4 d4 e4}")
  (m/parse "{chorus: g4 a4 b4}")
  (is (= [:chorus :verse] (m/ids))
      "both IDs registered and sorted"))

(deftest parse-error-returns-nil
  (is (nil? (m/parse "{c4 d4"))
      "unclosed brace returns nil"))

;; ============================================================
;; Resolution & find
;; ============================================================

(deftest find-by-keyword
  (m/parse "{verse: c4 d4}")
  (let [c (m/find :verse)]
    (is (d/composite? c))
    (is (= "verse" (:id c)))))

(deftest find-by-string
  (m/parse "{verse: c4 d4}")
  (is (d/composite? (m/find "verse"))))

(deftest find-by-index
  (m/parse "{verse: c4 d4}")
  (let [s (m/find 0)]
    (is (d/composite? s))
    (is (= :SCORE (:type s)))))

(deftest find-missing-returns-nil
  (is (nil? (m/find :nonexistent))))

;; ============================================================
;; Children & leaves
;; ============================================================

(deftest children-of-named-part
  (m/parse "{verse: c4 d4 e4}")
  (let [ch (m/children :verse)]
    (is (= 3 (count ch)) "verse has 3 children")
    (is (every? d/leaf? ch) "all are leaves")))

(deftest leaves-filters-rests
  (m/parse "{verse: c4 r4 d4}")
  (let [ls (m/leaves :verse)]
    (is (= 2 (count ls)) "only 2 pitched leaves")))

;; ============================================================
;; Context query
;; ============================================================

(deftest ctx-reads-dynamic
  (m/parse "{verse: !ff c4 d4}")
  (let [vol (m/ctx :verse :volume 0.0)]
    (is (some? vol) "volume is set")
    (is (number? vol) "volume is a number")
    (is (= 80 (int vol)) "ff = 80")))

(deftest ctx-reads-tempo
  (m/parse "{verse: !tempo:92 c4 d4}")
  (let [tempo (m/ctx :verse :Tempo 0.0)]
    (is (= 92 (int tempo)) "tempo = 92")))

;; ============================================================
;; Expand
;; ============================================================

(deftest expand-plain-leaf
  (m/parse "{verse: c4}")
  (let [leaf (first (m/leaves :verse))
        expanded (m/expand leaf)]
    (is (= 1 (count expanded)) "plain leaf => 1 sub-leaf")
    (is (= leaf (first expanded)) "unchanged")))

;; ============================================================
;; Collect (offline MIDI)
;; ============================================================

(deftest collect-produces-notes
  (m/parse "{verse: !mf c4 d4 e4}")
  (let [notes (m/collect :verse)]
    (is (seq notes) "produces at least one note")
    (is (every? map? notes) "notes are maps")))

;; ============================================================
;; Inspect (smoke — just verify no exceptions)
;; ============================================================

(deftest inspect-no-args-doesnt-throw
  (m/parse "{verse: c4 d4}")
  (is (nil? (m/inspect)) "inspect prints, returns nil"))

(deftest inspect-by-id-doesnt-throw
  (m/parse "{verse: c4 d4}")
  (is (nil? (m/inspect :verse)) "inspect by id prints, returns nil"))

;; ============================================================
;; Book
;; ============================================================

(deftest book-accumulates
  (m/parse "{a: c4}")
  (m/parse "{b: d4}")
  (is (= 2 (m/score-count)) "two scores in book")
  (is (= 2 (count (m/scores)))))

;; ============================================================
;; Reset
;; ============================================================

(deftest reset-clears-state
  (m/parse "{verse: c4 d4}")
  (m/reset)
  (is (= 0 (m/score-count)) "book cleared")
  (is (empty? (m/ids)) "registry cleared"))
