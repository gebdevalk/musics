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
