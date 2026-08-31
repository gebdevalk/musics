(ns ^:engine test-support-test
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries with-fresh-session]]
            [core.repo :as repo]
            [core.registries :as reg]
            [core.wall :as wall]))

(deftest with-fresh-registries-gives-a-genuinely-empty-repo
  (with-fresh-registries
    (is (nil? (repo/current :ROOT)) "no :ROOT seeded -- matches reset-all!'s own behavior")
    (is (= 0 @reg/*repo-tx-counter*))
    (is (empty? @reg/*wall-registry*))
    (is (empty? @reg/*preset-registry*))))

(deftest with-fresh-registries-is-genuinely-isolated-not-shared
  ;; register something in the OUTER (real, shared) registry, then
  ;; confirm it's invisible inside the binding, and unharmed after.
  (wall/register-wall! ::leak-check (fn [nodes _ _] nodes))
  (with-fresh-registries
    (is (nil? (wall/wall-fn ::leak-check))
        "the outer registration is invisible inside a fresh binding"))
  (is (some? (wall/wall-fn ::leak-check))
      "and the outer registration survives untouched once the binding exits"))

(deftest with-fresh-registries-mutations-dont-escape-the-binding
  (with-fresh-registries
    (wall/register-wall! ::inside-only (fn [nodes _ _] nodes))
    (is (some? (wall/wall-fn ::inside-only))))
  (is (nil? (wall/wall-fn ::inside-only))
      "a registration made INSIDE the binding is gone once it exits -- a
       fresh, throwaway atom, not the shared one"))

(deftest with-fresh-session-seeds-a-real-root
  (with-fresh-session
    (is (some? (repo/current :ROOT)))
    (is (= :ROOT (:id (repo/current :ROOT))))
    (is (empty? (:children (repo/current :ROOT))))))

(deftest with-fresh-session-nests-cleanly
  ;; two separate with-fresh-session blocks in a row never see each
  ;; other's committed material.
  (with-fresh-session
    (repo/commit-node! :verse {:type :SEQ :id :verse :context nil :children []}))
  (with-fresh-session
    (is (nil? (repo/current :verse))
        "the previous block's :verse never leaked into this fresh one")))
