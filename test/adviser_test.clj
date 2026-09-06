(ns ^:engine adviser-test
  (:require [clojure.test :refer [deftest is]]
            [test-support :refer [with-fresh-registries]]
            [core.adviser :as adviser]
            [core.repo :as repo]
            [core.wall :as wall]
            [core.async-engine :as engine]
            [core.domain.flat-domain :as d]
            [core.domain.context :as c]))

(defn- reset-everything! []
  ;; Registry isolation (repo/staging/tx-counter/wall/preset/conductor/
  ;; adviser) is handled by with-fresh-registries, which wraps every
  ;; test body -- this fn now only sets a fresh engine. It can't be
  ;; folded into a binding itself: a helper fn returns before the
  ;; caller's own body runs, so any binding scope started here would
  ;; already be closed by the time a test does anything -- same
  ;; reasoning the engine-isolation pass already applied to this exact
  ;; fn, still correct here.
  (engine/set-engine! (engine/engine nil repo/play-tx :ROOT)))

;; ============================================================
;; wipe! / log-activity! / recent-activity
;; ============================================================

(deftest wipe!-clears-the-log-without-touching-anything-else
  (with-fresh-registries
    (reset-everything!)
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {})
                               :children [:verse]})
    (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
    (adviser/log-activity! :parse {:sid :sid1})
    (adviser/wipe!)
    (is (empty? (adviser/recent-activity)))
    (is (= :verse (first (:children (repo/current :ROOT))))
        "wipe! never touches the repo")))

(deftest log-activity!-appends-and-recent-activity-reads-it-back
  (with-fresh-registries
    (reset-everything!)
    (adviser/log-activity! :parse {:sid :sid1})
    (adviser/log-activity! :commit! {:sid :sid1})
    (let [log (adviser/recent-activity)]
      (is (= 2 (count log)))
      (is (= [:parse :commit!] (mapv :action log))))))

(deftest log-activity!-caps-at-30-entries
  (with-fresh-registries
    (reset-everything!)
    (dotimes [i 40] (adviser/log-activity! :parse {:n i}))
    (let [log (adviser/recent-activity)]
      (is (= 30 (count log)))
      (is (= 10 (:n (:detail (first log))))
          "the oldest 10 were trimmed -- entry 0..9 gone, 10 is now the oldest kept"))))

;; ============================================================
;; what-next -- state-based candidates
;; ============================================================

(deftest what-next-suggests-writing-on-a-fresh-repo
  (with-fresh-registries
    (reset-everything!)
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children []})
    (is (re-find #"Nothing committed yet" (first (adviser/what-next))))))

(deftest what-next-flags-an-outstanding-staged-sid-first-regardless-of-anything-else
  (with-fresh-registries
    (reset-everything!)
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children []})
    (let [sid (repo/begin-staged-tx!)]
      (repo/stage! sid :verse {:type :SEQ :id :verse :context (c/context) :children []})
      (is (re-find #"Uncommitted staged edit" (first (adviser/what-next)))))))

(deftest what-next-suggests-playing-once-committed-but-never-played
  (with-fresh-registries
    (reset-everything!)
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
    (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
    (repo/play-latest!)
    (is (re-find #"haven't played anything yet" (first (adviser/what-next))))))

(deftest what-next-flags-a-stale-play-tx-as-the-most-urgent-candidate
  ;; The exact real scenario this candidate exists for: commit real
  ;; material, but never call play-latest!/play-tx! -- play-tx sits
  ;; behind the latest commit, so the NEXT (play ...)/(display ...)
  ;; would throw "No part found for id ... as of tx N" from
  ;; validate-ids!, confirmed live in a real session before this
  ;; candidate was added.
  (with-fresh-registries
    (reset-everything!)
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
    (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
    ;; deliberately NO (repo/play-latest!) here
    (is (re-find #"play-tx is behind the latest commit" (first (adviser/what-next))))))

(deftest what-next-does-not-flag-a-stale-play-tx-when-nothing-real-is-committed
  ;; A freshly-bootstrapped, still-empty :ROOT also leaves play-tx
  ;; behind latest-tx -- but there's nothing worth playing yet, so this
  ;; must NOT preempt the far more relevant "nothing committed yet"
  ;; candidate.
  (with-fresh-registries
    (reset-everything!)
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children []})
    (is (re-find #"Nothing committed yet" (first (adviser/what-next))))
    (is (not (some #(re-find #"play-tx is behind" %) (adviser/what-next 5))))))

(deftest what-next-notices-a-currently-playing-voice
  (with-fresh-registries
    (reset-everything!)
    (let [placeholder (d/leaf :ph (c/context) 1/4 [0])
          source      {:type :SEQ :id :s1 :context (c/context) :children [placeholder]}
          iter        (d/iterator :REPEAT :r1 (c/context) source {:count :infinite})
          verse       {:type :SEQ :id :verse :context (c/context) :children [iter]}
          root        {:type :ROOT :id :ROOT
                       :context (c/context-root {"Tempo" 6000 "volume" 80})
                       :children [:verse]}]
      (repo/commit-node! :ROOT root)
      (repo/commit-node! :verse verse)
      (repo/play-latest!)
      (let [eng (engine/engine nil repo/play-tx :ROOT)]
        (binding [engine/*engine* eng]
          (engine/play :verse)
          (adviser/log-activity! :play {:args [:verse]})
          (is (re-find #"currently playing" (first (adviser/what-next))))
          (engine/stop! eng))))))

(deftest what-next-flags-a-registered-but-unassigned-wall-algo
  (with-fresh-registries
    (reset-everything!)
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
    (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
    (adviser/log-activity! :play {:args [:verse]})
    (wall/register-algo! ::adviser-test-algo (fn [nodes _ _] nodes))
    (is (some #(re-find #"Algorithm\(s\) registered" %) (adviser/what-next 5)))))

;; ============================================================
;; explicit intent argument -- narrows priority, never hides, never stored
;; ============================================================

(deftest an-explicit-intent-reorders-but-doesnt-hide-other-candidates
  (with-fresh-registries
    (reset-everything!)
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
    (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
    (repo/play-latest!)
    (wall/register-algo! ::adviser-test-algo2 (fn [nodes _ _] nodes))
    ;; two tier-1 candidates true at once: :play (never played) and
    ;; :configure (wall registered, unassigned) -- passing :configure
    ;; should put ITS suggestion first, but the :play one still appears
    ;; somewhere in the list, not dropped.
    (let [suggestions (adviser/what-next 5 :configure)]
      (is (re-find #"Algorithm\(s\) registered" (first suggestions))
          "the passed intent's own candidate sorts first")
      (is (some #(re-find #"haven't played anything yet" %) suggestions)
          "the other true candidate still appears, just not first"))))

(deftest an-explicit-intent-can-be-given-by-1-based-position-too
  (with-fresh-registries
    (reset-everything!)
    (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]})
    (repo/commit-node! :verse {:type :SEQ :id :verse :context (c/context) :children []})
    (wall/register-algo! ::adviser-test-algo4 (fn [nodes _ _] nodes))
    ;; :configure is intents' own 4th entry -- (adviser/what-next 5 4)
    ;; must produce the exact same result as (adviser/what-next 5 :configure).
    (is (= (adviser/what-next 5 :configure) (adviser/what-next 5 4)))))

(deftest what-next-rejects-an-unrecognized-explicit-intent
  (with-fresh-registries
    (reset-everything!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a recognized intent"
          (adviser/what-next 3 :composting)))))

(deftest what-next-rejects-an-out-of-range-position
  (with-fresh-registries
    (reset-everything!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a recognized intent"
          (adviser/what-next 3 0)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a recognized intent"
          (adviser/what-next 3 99)))))

(deftest an-unrecognized-intent-error-shows-the-numbered-list
  (with-fresh-registries
    (reset-everything!)
    (try
      (adviser/what-next 3 :composting)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (re-find #"1\. :parse" (.getMessage e)))
        (is (re-find #"6\. :play" (.getMessage e)))))))
