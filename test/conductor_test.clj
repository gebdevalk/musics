(ns ^:engine conductor-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [core.conductor :as conductor]
            [core.repo :as repo]))

(defn reset-state-fixture [f]
  (reset! conductor/action-registry {})
  (reset! conductor/schedule {})
  (repo/reset-all!)
  (f))

(use-fixtures :each reset-state-fixture)

;; ============================================================
;; Action registry
;; ============================================================

(deftest register-and-trigger
  (let [calls (atom [])]
    (conductor/register-action! :fade-out (fn [voice] (swap! calls conj voice)))
    (conductor/trigger! :fade-out :voice-2)
    (is (= [:voice-2] @calls))))

(deftest trigger-unknown-id-is-a-noop
  (is (nil? (conductor/trigger! :bogus :whatever))))

(deftest unregister-action-forgets-it
  (conductor/register-action! :x (fn [] :called))
  (conductor/unregister-action! :x)
  (is (nil? (conductor/trigger! :x))))

;; ============================================================
;; Schedule + signal
;; ============================================================

(deftest signal-triggers-scheduled-action
  (let [fired (atom nil)]
    (conductor/register-action! :cue (fn [event] (reset! fired event)))
    (conductor/schedule! :verse :exit :cue)
    (conductor/signal! {:kind :section :id :verse :type :SEQ :phase :exit})
    (is (= :exit (:phase @fired)))))

(deftest signal-is-a-noop-when-nothing-scheduled
  (is (nil? (conductor/signal! {:kind :section :id :nobody-cares :phase :enter}))))

(deftest schedule-is-one-shot
  (let [calls (atom 0)]
    (conductor/register-action! :cue (fn [_] (swap! calls inc)))
    (conductor/schedule! :verse :exit :cue)
    (conductor/signal! {:id :verse :phase :exit})
    (conductor/signal! {:id :verse :phase :exit})
    (is (= 1 @calls) "consumed after the first signal, not re-fired")
    (is (nil? (conductor/scheduled :verse :exit)))))

(deftest unschedule-cancels-without-triggering
  (let [calls (atom 0)]
    (conductor/register-action! :cue (fn [_] (swap! calls inc)))
    (conductor/schedule! :verse :exit :cue)
    (conductor/unschedule! :verse :exit)
    (conductor/signal! {:id :verse :phase :exit})
    (is (zero? @calls))))

(deftest signal-only-matches-id-and-phase
  (let [calls (atom 0)]
    (conductor/register-action! :cue (fn [_] (swap! calls inc)))
    (conductor/schedule! :verse :exit :cue)
    (conductor/signal! {:id :verse :phase :enter})
    (conductor/signal! {:id :chorus :phase :exit})
    (is (zero? @calls) "wrong phase or wrong id never fires it")))

;; ============================================================
;; schedule-tx! -- the primary use case
;; ============================================================

(deftest schedule-tx-cuts-playback-over-on-signal
  (repo/commit-node! :ROOT {:type :ROOT})
  (let [tx1 (repo/latest-tx)]
    (repo/play-tx! tx1)
    (repo/commit-node! :verse {:type :SEQ})
    (let [tx2 (repo/latest-tx)]
      (is (= tx1 @repo/play-tx) "play-tx untouched by the plain commit")
      (conductor/schedule-tx! :verse :exit tx2)
      (is (= tx1 @repo/play-tx) "scheduling alone doesn't move it yet")
      (conductor/signal! {:id :verse :phase :exit})
      (is (= tx2 @repo/play-tx) "signal fired the cut-over"))))

(deftest schedule-tx-latest-resolves-at-fire-time-not-schedule-time
  (repo/commit-node! :ROOT {:type :ROOT})
  (repo/play-latest!)
  (conductor/schedule-tx! :verse :exit :latest)
  (repo/commit-node! :verse {:type :SEQ})     ;; committed AFTER scheduling
  (let [latest (repo/latest-tx)]
    (conductor/signal! {:id :verse :phase :exit})
    (is (= latest @repo/play-tx)
        "resolved :latest against the tx current when it fired, not when scheduled")))
