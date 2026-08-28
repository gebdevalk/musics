(ns ^:engine conductor-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [core.conductor :as conductor]
            [core.registries :as reg]
            [core.repo :as repo]))

(defn reset-state-fixture [f]
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {})
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

;; schedule-tx! moved to core.async-engine (it needs to know what a
;; voice is, which this namespace still never does) -- see
;; async-engine-test's own "cut-over" section for its tests.
