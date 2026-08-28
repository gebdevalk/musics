(ns ^:repl repo-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [core.registries :as reg]
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

(deftest view-seq-excludes-ids-not-yet-existing-at-the-pinned-tx
  (repo/commit-node! :a {:v 1})
  (repo/commit-node! :b {:v 2})
  (is (= {:a {:v 1}} (into {} (repo/view 1)))
      ":b committed at tx 2, invisible when pinned at tx 1")
  (is (= {:a {:v 1} :b {:v 2}} (into {} (repo/view 2)))))

(deftest view-seq-derefs-the-registry-once-per-call-not-once-per-id
  ;; Real cost this was fixed to avoid, review.txt point 15: seq used
  ;; to call as-of once PER id, each independently re-deref'ing
  ;; *repo-registry* -- N+1 derefs (the key list's own deref, plus one
  ;; more per id) for N ids. as-of-in now takes a snapshot deref'd
  ;; exactly once by seq itself and reuses it for every id lookup.
  (repo/commit-node! :a {:v 1})
  (repo/commit-node! :b {:v 2})
  (repo/commit-node! :c {:v 3})
  (let [deref-count (atom 0)
        snapshot    @reg/*repo-registry*]
    (binding [reg/*repo-registry*
              (reify clojure.lang.IDeref
                (deref [_] (swap! deref-count inc) snapshot))]
      (is (= #{:a :b :c} (set (keys (repo/view (repo/latest-tx))))
              (set (keys (into {} (repo/view (repo/latest-tx))))))
          "seq's result is unaffected by the swap -- same three ids")
      (is (= 2 @deref-count)
          "exactly one deref per seq call above (two calls total) -- not
           one per id on top of that (would be 8 total with the old
           per-id as-of implementation: (1 + 3) derefs x 2 calls)"))))
