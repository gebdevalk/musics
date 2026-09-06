(ns ^:algo logistic-test
  "Tests for the logistic map. Run: lein test logistic-test"
  (:require [clojure.test :refer [deftest is]]
            [algo.random.logistic :as lg]))

;; Rewritten 2026-09-03 against logistic-function directly -- the
;; module-level shared singleton (factor!/seed!/value) these tests used
;; to exercise was removed as genuinely dead code (unreferenced
;; anywhere except these two tests) that also carried a real footgun
;; (shared mutable state at namespace load time).

(deftest logistic-map-is-deterministic-given-seed
  (let [v1 ((:value (lg/logistic-function 3.0 0.5)))
        v2 ((:value (lg/logistic-function 3.0 0.5)))]
    (is (= v1 v2) "two fresh instances, same r/x, agree on their own first step")))

(deftest logistic-map-matches-formula
  (let [{:keys [value]} (lg/logistic-function 3.2 0.4)]
    (is (= (* 3.2 0.4 (- 1 0.4)) (value)))))
