(ns musics-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [musics :as m]
            [core.domain.flat-domain :as d]))

(defn reset-state-fixture [f]
  (reset! @#'m/book [])
  (f))

(use-fixtures :each reset-state-fixture)

;; ============================================================
;; Parse
;; ============================================================

(deftest parse-returns-score
  (let [score (m/parse "[verse: c4 d4]")]
    (is (instance? musics.Score score) "parse returns a Score")
    (is (= :verse (:id score)) "score has correct id")
    (is (d/container? (m/find :verse)) "id resolves to a container in tree")))

(deftest parse-registers-ids
  (m/parse "[verse: c4 d4]")
  (m/parse "[chorus: g4 a4 b4]")
  (let [all-ids (set (m/ids))]
    (is (all-ids :verse) "verse registered")
    (is (all-ids :chorus) "chorus registered")))

(deftest parse-error-returns-nil
  (binding [*out* (java.io.StringWriter.)]
    (is (nil? (m/parse "[c4 d4")) "unclosed bracket returns nil")))

;; ============================================================
;; Find
;; ============================================================

(deftest find-by-keyword
  (m/parse "[verse: c4 d4]")
  (let [c (m/find :verse)]
    (is (d/container? c))
    (is (= :verse (:id c)))))

(deftest find-by-string
  (m/parse "[verse: c4 d4]")
  (is (d/container? (m/find "verse"))))

(deftest find-by-index
  (m/parse "[verse: c4 d4]")
  (let [s (m/find 0)]
    (is (d/container? s) "root exists")
    (is (= :ROOT (:type s)) "root type is :ROOT")))

(deftest find-nonexistent-returns-nil
  (is (nil? (m/find :bogus)) "bogus keyword returns nil")
  (is (nil? (m/find 99)) "out-of-range index returns nil"))

;; ============================================================
;; Children / Leaves
;; ============================================================

(deftest children-of-named-part
  (m/parse "[verse: c4 d4]")
  (let [ch (m/children :verse)]
    (is (= 2 (count ch)) "two children")
    (is (every? d/leaf? ch) "both are leaves")))

(deftest leaves-of-named-part
  (m/parse "[verse: c4 d4]")
  (let [ls (m/leaves :verse)]
    (is (= 2 (count ls)) "two leaves")
    (is (every? d/leaf? ls) "both are leaves")))
