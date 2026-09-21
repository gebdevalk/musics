(ns ^:repl repo-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [test-support :refer [with-fresh-registries]]
            [core.repo :as repo]))

(use-fixtures :each (fn [f] (with-fresh-registries (f))))

;; ============================================================
;; changed-ids -- pure, no atoms touched
;; ============================================================

(deftest changed-ids-finds-new-and-modified-only
  (let [old {:a 1 :b 2}
        new {:a 1 :b 3 :c 4}]
    (is (= #{:b :c} (repo/changed-ids old new))
        "unchanged :a excluded; modified :b and new :c included")))

(deftest changed-ids-empty-when-nothing-differs
  (let [m {:a 1 :b 2}]
    (is (= #{} (repo/changed-ids m m)))))

(deftest changed-ids-ignores-removed-ids
  (let [old {:a 1 :b 2}
        new {:a 1}]
    (is (= #{} (repo/changed-ids old new))
        "changed-ids only looks at new-repo's own entries -- a caller
         that genuinely deletes ids needs its own mechanism for that,
         this just isn't it")))

;; ============================================================
;; commit-node! / current -- immediate single-id commit
;; ============================================================

(deftest commit-node-is-visible-immediately
  (repo/commit-node! :a {:v 1})
  (is (= {:v 1} (repo/current :a))))

(deftest commit-node-overwrites-whatever-was-there
  (repo/commit-node! :a {:v 1})
  (repo/commit-node! :a {:v 2})
  (is (= {:v 2} (repo/current :a))
      "no history retained -- the old value is simply gone"))

(deftest current-returns-nil-for-an-id-that-never-existed
  (is (nil? (repo/current :never-committed))))

;; ============================================================
;; commit-many! -- one atomic, immediate multi-id commit
;; ============================================================

(deftest commit-many-lands-every-pair-immediately
  (repo/commit-many! {:a {:v 1} :b {:v 2}})
  (is (= {:v 1} (repo/current :a)))
  (is (= {:v 2} (repo/current :b))))

(deftest commit-many-is-a-no-op-for-empty-edits
  (is (nil? (repo/commit-many! {}))))

;; ============================================================
;; registry -- the live {id -> node} atom itself
;; ============================================================

(deftest registry-reflects-every-commit
  (repo/commit-node! :a {:v 1})
  (repo/commit-many! {:b {:v 2} :c {:v 3}})
  (is (= {:a {:v 1} :b {:v 2} :c {:v 3}} @(repo/registry))))

;; ============================================================
;; seed! / reset-all!
;; ============================================================

(deftest seed-replaces-whatever-was-committed-before
  (repo/commit-node! :a {:v 1})
  (repo/seed! {:b {:v 2}})
  (is (nil? (repo/current :a)) "discarded by seed!")
  (is (= {:v 2} (repo/current :b))))

(deftest reset-all-clears-the-registry
  (repo/commit-node! :a {:v 1})
  (repo/reset-all!)
  (is (= {} @(repo/registry))))
