(ns ^:repl repo-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [core.repo :as repo]))

(use-fixtures :each (fn [f] (repo/reset-all!) (f)))

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
;; stage-many! -- one swap!, same effect as stage! in a loop
;; ============================================================

(deftest stage-many-records-every-pair
  (let [sid (repo/begin-staged-tx!)]
    (repo/stage-many! sid {:a {:v 1} :b {:v 2}})
    (is (= {:a {:v 1} :b {:v 2}} (repo/staged-edits sid)))))

(deftest stage-many-merges-with-earlier-stage-calls
  (let [sid (repo/begin-staged-tx!)]
    (repo/stage! sid :a {:v 1})
    (repo/stage-many! sid {:b {:v 2} :c {:v 3}})
    (is (= {:a {:v 1} :b {:v 2} :c {:v 3}} (repo/staged-edits sid)))))

(deftest stage-many-then-commit-lands-all-under-one-tx
  (let [sid (repo/begin-staged-tx!)]
    (repo/stage-many! sid {:a {:v 1} :b {:v 2}})
    (let [tx (repo/commit-staged! sid)]
      (is (= {:v 1} (repo/as-of :a tx)))
      (is (= {:v 2} (repo/as-of :b tx)))
      (is (nil? (repo/staged-edits sid)) "staging area cleared after commit"))))

;; ============================================================
;; as-of -- nil for "didn't exist yet", not an NPE
;; ============================================================

(deftest as-of-returns-nil-before-id-existed
  ;; Real bug, found live: as-of called (val (first (rsubseq versions <=
  ;; tx))) unconditionally -- when the id exists in the registry but has
  ;; no version at-or-before tx (committed later), rsubseq/first is nil
  ;; and (val nil) NPEs, instead of returning nil per as-of's own
  ;; docstring ("or nil if it didn't exist yet").
  (repo/commit-node! :a {:v 1})
  (is (nil? (repo/as-of :a 0))
      "committed at tx 1 -- as-of at tx 0 must return nil, not throw"))

(deftest view-get-returns-nil-before-id-existed
  ;; view's ILookup (get repo id) is exactly the path play/resolve-
  ;; context-ref use to resolve a keyword against a pinned tx -- it
  ;; delegates straight to as-of, so it shared the same NPE.
  (repo/commit-node! :a {:v 1})
  (is (nil? (get (repo/view 0) :a))
      "view pinned at tx 0, id only exists from tx 1 on"))
