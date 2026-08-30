(ns ^:engine wall-preset-test
  (:require [clojure.test :refer [deftest is]]
            [core.repo :as repo]
            [core.registries :as reg]
            [core.wall :as wall]
            [core.async-engine :as engine]
            [core.domain.context :as c]
            [core.domain.flat-domain :as d]))

(defn- reset-everything! []
  (repo/reset-all!)
  (reset! reg/*wall-registry* {})
  (reset! reg/*preset-registry* {})
  (reset! reg/*conductor-action-registry* {})
  (reset! reg/*conductor-schedule* {})
  (reset! reg/*conductor-repeating* {}))

;; A tiny, deterministic factory: (fn [a b] -> wall fn), the wall fn
;; itself just stamps [a b] onto every node it sees, so a preset's own
;; config is trivially observable without needing real MIDI/playback.
(defn- stamp-factory [a b]
  (fn [nodes _ctx _voice] (map #(assoc % :stamp [a b]) nodes)))

;; ============================================================
;; configure-preset! -- independent presets off one factory
;; ============================================================

(deftest two-presets-off-one-factory-stay-independent
  (reset-everything!)
  (wall/register-wall! ::stamp stamp-factory nil :factory)
  (wall/configure-preset! ::bright ::stamp 1 2)
  (wall/configure-preset! ::dark   ::stamp 9 9)
  (is (= [{:stamp [1 2]}] ((wall/preset-fn ::bright) [{}] [] nil)))
  (is (= [{:stamp [9 9]}] ((wall/preset-fn ::dark) [{}] [] nil)))
  (is (= :factory (wall/wall-kind ::stamp))
      "factory-name's own wall-registry entry is only ever READ, never
       overwritten -- unlike configure-wall!, which would have turned
       ::stamp itself into a resolved :fn after the first configure"))

(deftest configure-preset!-preserves-the-factorys-own-doc
  (reset-everything!)
  (wall/register-wall! ::stamp stamp-factory "stamps [a b] onto every node" :factory)
  (wall/configure-preset! ::bright ::stamp 1 2)
  (is (= "stamps [a b] onto every node" (wall/presets ::bright))))

(deftest unregistered-factory-name-warns-and-leaves-prior-registration-untouched
  (reset-everything!)
  (wall/register-wall! ::stamp stamp-factory nil :factory)
  (wall/configure-preset! ::bright ::stamp 1 2)
  (wall/configure-preset! ::bright ::nonexistent 5 5)
  (is (= [{:stamp [1 2]}] ((wall/preset-fn ::bright) [{}] [] nil))
      "a failed configure-preset! (bad factory-name) leaves ::bright's
       PRIOR preset in place, same no-partial-overwrite policy
       apply-factory already has everywhere else"))

;; ============================================================
;; resolve-config-form -- args can be literals, repo Data, or groups
;; ============================================================

(deftest configure-preset!-args-are-plain-literals-by-default
  (reset-everything!)
  (wall/register-wall! ::stamp stamp-factory nil :factory)
  (wall/configure-preset! ::p ::stamp [60 62 64] 1/4)
  (is (= [{:stamp [[60 62 64] 1/4]}] ((wall/preset-fn ::p) [{}] [] nil))
      "a literal vector with nothing keyword-shaped in it passes through unchanged"))

(deftest configure-preset!-args-resolve-real-repo-data
  (reset-everything!)
  (let [talea {:type :DATA :id :myTalea :context (c/context)
               :children [1/4 1/8 1/8 1/4]}
        root  {:type :ROOT :id :ROOT :context (c/context-root {})
               :children [:myTalea]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :myTalea talea))
  (wall/register-wall! ::stamp stamp-factory nil :factory)
  (wall/configure-preset! ::p ::stamp :myTalea 0)
  (is (= [{:stamp [[1/4 1/8 1/8 1/4] 0]}] ((wall/preset-fn ::p) [{}] [] nil))
      "a bare keyword resolving to a :DATA container pulls its raw
       committed values, not anything Leaf/voice-shaped"))

(deftest configure-preset!-unresolvable-keyword-falls-back-to-literal
  (reset-everything!)
  (repo/commit-node! :ROOT {:type :ROOT :id :ROOT :context (c/context-root {}) :children []})
  (wall/register-wall! ::stamp stamp-factory nil :factory)
  (wall/configure-preset! ::p ::stamp :major 0)
  (is (= [{:stamp [:major 0]}] ((wall/preset-fn ::p) [{}] [] nil))
      "an id that names nothing in the repo is treated as an ordinary
       literal keyword flag, not an error -- factory args are routinely
       plain flags, not repo references"))

(deftest configure-preset!-resolves-groups-recursively-preserving-collection-type
  (reset-everything!)
  (let [color {:type :DATA :id :myColor :context (c/context) :children [60 62 64]}
        root  {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:myColor]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :myColor color))
  (wall/register-wall! ::stamp stamp-factory nil :factory)
  (wall/configure-preset! ::p ::stamp [:myColor :flag] #{1 :myColor})
  (is (= [{:stamp [[[60 62 64] :flag] #{1 [60 62 64]}]}]
         ((wall/preset-fn ::p) [{}] [] nil))
      "a vector stays a vector, a set stays a set -- each item resolved
       independently, :flag unresolvable so passed through as-is"))

;; ============================================================
;; presets are reachable through the exact same assign-algo!/play
;; :algo mechanism every other Name shape already uses
;; ============================================================

(deftest a-preset-name-resolves-through-assign-algo!-and-doesnt-fail-validation
  (reset-everything!)
  (let [n1    (c/context)
        verse {:type :SEQ :id :verse :context (c/context)
               :children [(d/leaf :n1 n1 1/4 [60])]}
        root  {:type :ROOT :id :ROOT :context (c/context-root {}) :children [:verse]}]
    (repo/commit-node! :ROOT root)
    (repo/commit-node! :verse verse))
  (wall/register-wall! ::stamp stamp-factory nil :factory)
  (wall/configure-preset! ::bright ::stamp 1 2)
  (repo/play-latest!)
  (let [eng (engine/engine nil repo/play-tx :ROOT)]
    (engine/set-engine! eng)
    ;; play-top-level!'s own validate-algo-name! runs BEFORE the flush --
    ;; if a preset name were still rejected as "unregistered", this
    ;; whole call would throw instead of returning normally.
    (let [id (engine/play :verse :algo ::bright)]
      (is (= (wall/preset-fn ::bright) (:fn (get @(:algo-assignments eng) [id]))))
      (engine/stop! eng))))

(deftest unregister-preset!-forgets-it-without-touching-the-underlying-factory
  (reset-everything!)
  (wall/register-wall! ::stamp stamp-factory nil :factory)
  (wall/configure-preset! ::bright ::stamp 1 2)
  (wall/unregister-preset! ::bright)
  (is (nil? (wall/preset-fn ::bright)))
  (is (= :factory (wall/wall-kind ::stamp)) "the factory itself is untouched"))
